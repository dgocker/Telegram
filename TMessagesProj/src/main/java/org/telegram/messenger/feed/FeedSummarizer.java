package org.telegram.messenger.feed;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.extensions.OrtxPackage;

/**
 * On-device summarization with rut5_base_sum_gazeta (INT8 ONNX).
 * Model contract:
 *   encoder: input_ids, attention_mask (int64 [b, seq]) -> last_hidden_state [b, seq, 768]
 *   decoder (merged prefill+cache): input_ids, encoder_attention_mask, encoder_hidden_states,
 *     use_cache_branch (bool) + past_key_values.{0..11}.{decoder,encoder}.{key,value} -> logits [b, s, 30000] + present.*
 *   tokenizer_pre: inputs (string) -> tokens (int32 flat)
 *   tokenizer_post: ids (int64) -> str (string)
 *   eos=1, pad=0, decoder_start_token_id=2, 12 layers, 12 heads, 64 head_dim, vocab=30000
 */
public class FeedSummarizer {

    public static final int LEN_SHORT = 0;
    public static final int LEN_MEDIUM = 1;
    public static final int LEN_DETAILED = 2;

    private static final int LAYERS = 12;
    private static final int HEADS = 12;
    private static final int HEAD_DIM = 64;
    private static final int EOS_ID = 1;
    private static final int DECODER_START_ID = 2;
    private static final int MAX_INPUT_TOKENS = 512;
    public static final int MIN_TEXT_LENGTH_TO_SUMMARIZE = 250;

    public static class ModelFile {
        public final String name;
        public final long size;
        ModelFile(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    public static final String MODEL_BASE_URL = "http://78.17.74.156/telegram-smartfeed/models/";
    public static final ModelFile[] MODEL_FILES = {
        new ModelFile("encoder_model_quantized.onnx", 108460259L),
        new ModelFile("decoder_model_merged_quantized.onnx", 160934378L),
        new ModelFile("tokenizer_pre.onnx", 828044L),
        new ModelFile("tokenizer_post.onnx", 827789L)
    };

    public static long getTotalModelSize() {
        long total = 0;
        for (ModelFile f : MODEL_FILES) {
            total += f.size;
        }
        return total;
    }

    public static File getModelDir() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "smartfeed_models");
    }

    public static boolean isModelDownloaded() {
        for (ModelFile f : MODEL_FILES) {
            File file = new File(getModelDir(), f.name);
            if (!file.exists() || file.length() != f.size) {
                return false;
            }
        }
        return true;
    }

    public static int getSummaryLength() {
        return MessagesController.getGlobalMainSettings().getInt("smartFeedSummaryLen", LEN_MEDIUM);
    }

    public static void setSummaryLength(int value) {
        MessagesController.getGlobalMainSettings().edit().putInt("smartFeedSummaryLen", value).apply();
    }

    private static volatile FeedSummarizer instance;

    public static FeedSummarizer getInstance() {
        FeedSummarizer localInstance = instance;
        if (localInstance == null) {
            synchronized (FeedSummarizer.class) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new FeedSummarizer();
                }
            }
        }
        return localInstance;
    }

    private final DispatchQueue queue = new DispatchQueue("feedSummarizer");
    private OrtEnvironment env;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private OrtSession tokenizerPreSession;
    private OrtSession tokenizerPostSession;
    private boolean initFailed;

    private final HashMap<String, String> memoryCache = new HashMap<>();

    private static SharedPreferences getCachePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("feed_summaries", 0);
    }

    public String getCached(long dialogId, int messageId) {
        final String key = dialogId + "_" + messageId + "_" + getSummaryLength();
        synchronized (memoryCache) {
            String cached = memoryCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        String persisted = getCachePrefs().getString(key, null);
        if (persisted != null) {
            synchronized (memoryCache) {
                memoryCache.put(key, persisted);
            }
        }
        return persisted;
    }

    /**
     * Асинхронная выжимка. Колбэк приходит на UI-потоке; null — модель недоступна или ошибка.
     */
    public void summarize(long dialogId, int messageId, String text, Utilities.Callback<String> onDone) {
        final String key = dialogId + "_" + messageId + "_" + getSummaryLength();
        queue.postRunnable(() -> {
            String result = null;
            try {
                result = summarizeSync(text);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (result != null) {
                synchronized (memoryCache) {
                    memoryCache.put(key, result);
                }
                SharedPreferences prefs = getCachePrefs();
                if (prefs.getAll().size() > 500) {
                    prefs.edit().clear().apply();
                }
                prefs.edit().putString(key, result).apply();
            }
            final String finalResult = result;
            AndroidUtilities.runOnUIThread(() -> onDone.run(finalResult));
        });
    }

    private boolean ensureInit() {
        if (encoderSession != null) {
            return true;
        }
        if (initFailed || !isModelDownloaded()) {
            return false;
        }
        try {
            env = OrtEnvironment.getEnvironment();
            File dir = getModelDir();
            encoderSession = createSession(new File(dir, "encoder_model_quantized.onnx"), false);
            decoderSession = createSession(new File(dir, "decoder_model_merged_quantized.onnx"), false);
            tokenizerPreSession = createSession(new File(dir, "tokenizer_pre.onnx"), true);
            tokenizerPostSession = createSession(new File(dir, "tokenizer_post.onnx"), true);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            initFailed = true;
            releaseSessions();
            return false;
        }
    }

    private OrtSession createSession(File file, boolean withExtensions) throws Exception {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        if (withExtensions) {
            opts.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
        }
        opts.setIntraOpNumThreads(4);
        return env.createSession(file.getAbsolutePath(), opts);
    }

    private void releaseSessions() {
        try {
            if (encoderSession != null) encoderSession.close();
            if (decoderSession != null) decoderSession.close();
            if (tokenizerPreSession != null) tokenizerPreSession.close();
            if (tokenizerPostSession != null) tokenizerPostSession.close();
        } catch (Throwable ignore) {}
        encoderSession = decoderSession = tokenizerPreSession = tokenizerPostSession = null;
    }

    private int[] maxNewTokensForSetting() {
        switch (getSummaryLength()) {
            case LEN_SHORT:
                return new int[]{8, 40};
            case LEN_DETAILED:
                return new int[]{32, 160};
            case LEN_MEDIUM:
            default:
                return new int[]{16, 80};
        }
    }

    private String summarizeSync(String text) throws Exception {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        text = text.trim();
        if (text.length() < MIN_TEXT_LENGTH_TO_SUMMARIZE) {
            // короткие посты не сжимаем — выжимка была бы длиннее оригинала
            return text;
        }
        if (!ensureInit()) {
            return null;
        }

        long[] inputIds = tokenize(text);
        if (inputIds == null || inputIds.length == 0) {
            return null;
        }

        final int[] minMax = maxNewTokensForSetting();
        final int minNewTokens = minMax[0];
        final int maxNewTokens = minMax[1];

        long[] attnMask = new long[inputIds.length];
        for (int i = 0; i < attnMask.length; i++) {
            attnMask[i] = 1;
        }
        long[] seqShape = {1, inputIds.length};

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), seqShape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attnMask), seqShape)) {

            Map<String, OnnxTensor> encoderInputs = new HashMap<>();
            encoderInputs.put("input_ids", idsTensor);
            encoderInputs.put("attention_mask", maskTensor);

            try (OrtSession.Result encoderResult = encoderSession.run(encoderInputs)) {
                OnnxTensor hiddenState = (OnnxTensor) encoderResult.get("last_hidden_state").get();
                return decodeGreedy(hiddenState, maskTensor, minNewTokens, maxNewTokens);
            }
        }
    }

    private String decodeGreedy(OnnxTensor encoderHiddenState, OnnxTensor encoderAttnMask, int minNewTokens, int maxNewTokens) throws Exception {
        final java.util.ArrayList<Long> generated = new java.util.ArrayList<>();
        final HashSet<Long> seenTokens = new HashSet<>();

        OrtSession.Result prefillResult = null;
        OrtSession.Result prevResult = null;
        try (OnnxTensor useCacheFalse = OnnxTensor.createTensor(env, new boolean[]{false});
             OnnxTensor useCacheTrue = OnnxTensor.createTensor(env, new boolean[]{true});
             OnnxTensor emptyPast = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{1, HEADS, 0, HEAD_DIM})) {

            long nextToken = DECODER_START_ID;
            for (int step = 0; step < maxNewTokens; step++) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                try (OnnxTensor stepIds = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{nextToken}), new long[]{1, 1})) {
                    inputs.put("input_ids", stepIds);
                    inputs.put("encoder_attention_mask", encoderAttnMask);
                    inputs.put("encoder_hidden_states", encoderHiddenState);
                    inputs.put("use_cache_branch", step == 0 ? useCacheFalse : useCacheTrue);
                    for (int l = 0; l < LAYERS; l++) {
                        if (step == 0) {
                            inputs.put("past_key_values." + l + ".decoder.key", emptyPast);
                            inputs.put("past_key_values." + l + ".decoder.value", emptyPast);
                            inputs.put("past_key_values." + l + ".encoder.key", emptyPast);
                            inputs.put("past_key_values." + l + ".encoder.value", emptyPast);
                        } else {
                            inputs.put("past_key_values." + l + ".decoder.key", (OnnxTensor) prevResult.get("present." + l + ".decoder.key").get());
                            inputs.put("past_key_values." + l + ".decoder.value", (OnnxTensor) prevResult.get("present." + l + ".decoder.value").get());
                            inputs.put("past_key_values." + l + ".encoder.key", (OnnxTensor) prefillResult.get("present." + l + ".encoder.key").get());
                            inputs.put("past_key_values." + l + ".encoder.value", (OnnxTensor) prefillResult.get("present." + l + ".encoder.value").get());
                        }
                    }

                    OrtSession.Result result = decoderSession.run(inputs);
                    if (step == 0) {
                        prefillResult = result;
                    } else if (prevResult != null && prevResult != prefillResult) {
                        prevResult.close();
                    }
                    prevResult = result;

                    float[][][] logits = (float[][][]) ((OnnxValue) result.get("logits").get()).getValue();
                    float[] last = logits[0][logits[0].length - 1];

                    // лёгкий штраф за повторы, чтобы greedy не зацикливался
                    for (long seen : seenTokens) {
                        int idx = (int) seen;
                        if (idx >= 0 && idx < last.length) {
                            if (last[idx] > 0) {
                                last[idx] /= 1.3f;
                            } else {
                                last[idx] *= 1.3f;
                            }
                        }
                    }
                    if (generated.size() < minNewTokens) {
                        last[EOS_ID] = Float.NEGATIVE_INFINITY;
                    }

                    int best = 0;
                    float bestVal = Float.NEGATIVE_INFINITY;
                    for (int i = 0; i < last.length; i++) {
                        if (last[i] > bestVal) {
                            bestVal = last[i];
                            best = i;
                        }
                    }
                    if (best == EOS_ID) {
                        break;
                    }
                    generated.add((long) best);
                    seenTokens.add((long) best);
                    nextToken = best;
                }
            }
        } finally {
            if (prevResult != null && prevResult != prefillResult) {
                prevResult.close();
            }
            if (prefillResult != null) {
                prefillResult.close();
            }
        }

        if (generated.isEmpty()) {
            return null;
        }
        long[] ids = new long[generated.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = generated.get(i);
        }
        return detokenize(ids);
    }

    private long[] tokenize(String text) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor input = OnnxTensor.createTensor(env, new String[]{text}, new long[]{1})) {
            inputs.put("inputs", input);
            try (OrtSession.Result result = tokenizerPreSession.run(inputs)) {
                Object value = ((OnnxValue) result.get("tokens").get()).getValue();
                int[] tokens;
                if (value instanceof int[]) {
                    tokens = (int[]) value;
                } else if (value instanceof int[][]) {
                    tokens = ((int[][]) value)[0];
                } else {
                    return null;
                }
                int len = Math.min(tokens.length, MAX_INPUT_TOKENS - 1);
                boolean needEos = len == 0 || tokens[len - 1] != EOS_ID;
                long[] ids = new long[len + (needEos ? 1 : 0)];
                for (int i = 0; i < len; i++) {
                    ids[i] = tokens[i];
                }
                if (needEos) {
                    ids[len] = EOS_ID;
                }
                return ids;
            }
        }
    }

    private String detokenize(long[] ids) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor input = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{ids.length})) {
            inputs.put("ids", input);
            try (OrtSession.Result result = tokenizerPostSession.run(inputs)) {
                Object value = ((OnnxValue) result.get("str").get()).getValue();
                String s;
                if (value instanceof String[]) {
                    String[] arr = (String[]) value;
                    s = arr.length > 0 ? arr[0] : null;
                } else if (value instanceof String) {
                    s = (String) value;
                } else {
                    return null;
                }
                if (s != null) {
                    s = s.trim();
                }
                return TextUtils.isEmpty(s) ? null : s;
            }
        }
    }
}
