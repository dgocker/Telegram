package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.core.content.ContextCompat;

import android.graphics.drawable.Drawable;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Грид-раскладка медиа поста (альбома) по геометрии MessageObject.GroupedMessages,
 * как в ChatMessageCell: pw/1000 — доля ширины, ph — доля высоты ряда.
 */
public class FeedMediaGrid extends ViewGroup {

    public interface OnMediaClickListener {
        void onMediaClick(int index);
    }

    private static final float HEIGHT_SCALE = 814f / 800f;

    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private MessageObject.GroupedMessages groupedMessages;
    private OnMediaClickListener onMediaClickListener;
    private final ArrayList<ItemView> itemViews = new ArrayList<>();
    private int measuredContentHeight;

    public FeedMediaGrid(Context context) {
        super(context);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        setClipToOutline(true);
    }

    public void setOnMediaClickListener(OnMediaClickListener listener) {
        onMediaClickListener = listener;
    }

    public void setMessages(ArrayList<MessageObject> album, MessageObject.GroupedMessages group) {
        messages.clear();
        if (album != null) {
            messages.addAll(album);
        }
        groupedMessages = group;

        while (itemViews.size() < messages.size()) {
            ItemView view = new ItemView(getContext());
            final int index = itemViews.size();
            view.setOnClickListener(v -> {
                if (onMediaClickListener != null) {
                    onMediaClickListener.onMediaClick(index);
                }
            });
            itemViews.add(view);
            addView(view);
        }
        // одиночное видео автоплеится прямо в ленте (без звука, как в чатах)
        final boolean autoplay = messages.size() == 1 && messages.get(0).isVideo() && SharedConfig.isAutoplayVideo();
        for (int i = 0; i < itemViews.size(); i++) {
            ItemView view = itemViews.get(i);
            if (i < messages.size()) {
                view.setVisibility(VISIBLE);
                view.bind(messages.get(i), autoplay);
            } else {
                view.setVisibility(GONE);
            }
        }
        requestLayout();
    }

    public BackupImageView getImageViewAt(int index) {
        return index >= 0 && index < itemViews.size() ? itemViews.get(index) : null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (messages.isEmpty() || width <= 0) {
            setMeasuredDimension(width, 0);
            return;
        }

        if (messages.size() == 1 || groupedMessages == null) {
            // одиночное фото/видео: по аспекту, с ограничениями
            MessageObject message = messages.get(0);
            float aspect = 1.5f;
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, 640);
            if (photoSize != null && photoSize.w > 0 && photoSize.h > 0) {
                aspect = (float) photoSize.w / photoSize.h;
            }
            int height = (int) (width / aspect);
            height = Math.max((int) (width * 0.4f), Math.min((int) (width * 1.25f), height));
            measureItem(itemViews.get(0), width, height);
            layoutRects.clear();
            layoutRects.add(new int[]{0, 0, width, height});
            measuredContentHeight = height;
            setMeasuredDimension(width, height);
            return;
        }

        // раскладка по GroupedMessagePosition
        layoutRects.clear();
        int maxY = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject.GroupedMessagePosition pos = groupedMessages.getPosition(messages.get(i));
            if (pos != null) {
                maxY = Math.max(maxY, pos.maxY);
            }
        }
        float[] rowHeights = new float[maxY + 1];
        for (int i = 0; i < messages.size(); i++) {
            MessageObject.GroupedMessagePosition pos = groupedMessages.getPosition(messages.get(i));
            if (pos == null) {
                continue;
            }
            if (pos.minY == pos.maxY) {
                rowHeights[pos.minY] = Math.max(rowHeights[pos.minY], pos.ph);
            } else if (pos.siblingHeights != null) {
                for (int r = 0; r < pos.siblingHeights.length && pos.minY + r <= maxY; r++) {
                    rowHeights[pos.minY + r] = Math.max(rowHeights[pos.minY + r], pos.siblingHeights[r]);
                }
            }
        }
        float[] rowTops = new float[maxY + 2];
        for (int r = 0; r <= maxY; r++) {
            if (rowHeights[r] <= 0) {
                rowHeights[r] = 0.5f;
            }
            rowTops[r + 1] = rowTops[r] + rowHeights[r];
        }
        final float heightScale = width * HEIGHT_SCALE;
        float[] rowX = new float[maxY + 1];

        int totalHeight = (int) (rowTops[maxY + 1] * heightScale);
        totalHeight = Math.min(totalHeight, (int) (width * 1.4f));
        final float clampScale = rowTops[maxY + 1] * heightScale > 0 ? totalHeight / (rowTops[maxY + 1] * heightScale) : 1f;

        for (int i = 0; i < messages.size(); i++) {
            MessageObject.GroupedMessagePosition pos = groupedMessages.getPosition(messages.get(i));
            int l, t, r, b;
            if (pos == null) {
                l = 0; t = 0; r = width; b = (int) (rowHeights[0] * heightScale * clampScale);
            } else {
                float x = rowX[pos.minY] + pos.leftSpanOffset / 1000f * width;
                float w = pos.pw / 1000f * width;
                rowX[pos.minY] += w + (pos.leftSpanOffset > 0 ? pos.leftSpanOffset / 1000f * width : 0);
                float top = rowTops[pos.minY] * heightScale * clampScale;
                float bottom = rowTops[Math.min(maxY + 1, pos.maxY + 1)] * heightScale * clampScale;
                l = Math.round(x);
                t = Math.round(top);
                r = Math.round(x + w);
                b = Math.round(bottom);
                if (pos.maxX >= getMaxColumn(pos.minY)) {
                    r = width; // растягиваем крайний в ряду до края
                }
            }
            layoutRects.add(new int[]{l, t, r, b});
            measureItem(itemViews.get(i), Math.max(1, r - l), Math.max(1, b - t));
        }
        measuredContentHeight = totalHeight;
        setMeasuredDimension(width, totalHeight);
    }

    private int getMaxColumn(int row) {
        int max = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject.GroupedMessagePosition pos = groupedMessages != null ? groupedMessages.getPosition(messages.get(i)) : null;
            if (pos != null && pos.minY <= row && row <= pos.maxY) {
                max = Math.max(max, pos.maxX);
            }
        }
        return max;
    }

    private final ArrayList<int[]> layoutRects = new ArrayList<>();

    private void measureItem(View child, int width, int height) {
        child.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int gap = dp(1);
        for (int i = 0; i < layoutRects.size() && i < itemViews.size(); i++) {
            int[] rect = layoutRects.get(i);
            int left = rect[0] == 0 ? rect[0] : rect[0] + gap;
            int top = rect[1] == 0 ? rect[1] : rect[1] + gap;
            itemViews.get(i).layout(left, top, rect[2], rect[3]);
        }
    }

    public static class ItemView extends BackupImageView {

        private boolean isVideo;
        private Drawable playDrawable;

        public ItemView(Context context) {
            super(context);
        }

        public void bind(MessageObject messageObject, boolean autoplay) {
            TLRPC.Document document = messageObject.getDocument();
            isVideo = messageObject.isVideo() && !(autoplay && document != null);
            if (isVideo && playDrawable == null) {
                playDrawable = ContextCompat.getDrawable(getContext(), R.drawable.play_mini_video).mutate();
            }
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 640);
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50);
            if (thumbSize == photoSize) {
                thumbSize = null;
            }
            if (autoplay && document != null) {
                getImageReceiver().setAllowStartAnimation(true);
                getImageReceiver().setImage(
                    ImageLocation.getForDocument(document), ImageLoader.AUTOPLAY_FILTER,
                    ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject), "640_640",
                    ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject), "50_50_b",
                    null, document.size, null, messageObject, 0);
            } else {
                getImageReceiver().setImage(
                    ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject), "640_640",
                    ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject), "50_50_b",
                    photoSize != null ? photoSize.size : 0, null, messageObject, 1);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (isVideo && playDrawable != null) {
                final int w = playDrawable.getIntrinsicWidth();
                final int h = playDrawable.getIntrinsicHeight();
                final int cx = getWidth() / 2;
                final int cy = getHeight() / 2;
                playDrawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
                playDrawable.draw(canvas);
            }
        }
    }
}
