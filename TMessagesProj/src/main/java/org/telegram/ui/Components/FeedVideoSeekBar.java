package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;

/**
 * Полоса перемотки видео в TikTok-стиле: тонкая линия прогресса при
 * воспроизведении, при касании разворачивается в большую со скраббером
 * и таймкодами. Прогресс/seek прокидываются через колбэки.
 */
public class FeedVideoSeekBar extends View {

    public interface Delegate {
        int getDurationMs();
        int getProgressMs();
        void onSeek(int ms);
        void onDragStart();
        void onDragEnd();
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Delegate delegate;
    private boolean dragging;
    private float dragProgress;
    private float expand; // 0 — тонкая линия, 1 — раскрытая
    private long lastFrameTime;

    public FeedVideoSeekBar(Context context) {
        super(context);
        bgPaint.setColor(0x40FFFFFF);
        fgPaint.setColor(Color.WHITE);
        knobPaint.setColor(Color.WHITE);
        timePaint.setColor(Color.WHITE);
        timePaint.setTextSize(dp(13));
        timePaint.setShadowLayer(dp(2), 0, dp(1), 0xB3000000);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    private float currentProgress() {
        if (dragging) {
            return dragProgress;
        }
        if (delegate == null) {
            return 0;
        }
        int dur = delegate.getDurationMs();
        return dur > 0 ? MathUtils.clamp(delegate.getProgressMs() / (float) dur, 0f, 1f) : 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (delegate == null || delegate.getDurationMs() <= 0) {
            return false;
        }
        final float pad = dp(12);
        final float usable = getWidth() - pad * 2;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                // не отдавать горизонтальный жест ViewPager табов (иначе листает на соседний таб)
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                dragProgress = MathUtils.clamp((event.getX() - pad) / usable, 0f, 1f);
                delegate.onDragStart();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                dragProgress = MathUtils.clamp((event.getX() - pad) / usable, 0f, 1f);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    delegate.onSeek((int) (dragProgress * delegate.getDurationMs()));
                    delegate.onDragEnd();
                    dragging = false;
                    invalidate();
                }
                return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // плавное раскрытие при драге
        long now = android.os.SystemClock.elapsedRealtime();
        float dt = lastFrameTime == 0 ? 0 : (now - lastFrameTime) / 140f;
        lastFrameTime = now;
        float target = dragging ? 1f : 0f;
        if (expand != target) {
            if (expand < target) {
                expand = Math.min(target, expand + dt);
            } else {
                expand = Math.max(target, expand - dt);
            }
            invalidate();
        }

        final float pad = dp(12);
        final float usable = getWidth() - pad * 2;
        final float trackH = AndroidUtilities.lerp(dp(2.5f), dp(6), expand);
        final float cy = getHeight() - dp(8) - trackH / 2f;
        final float progress = currentProgress();

        rect.set(pad, cy - trackH / 2f, pad + usable, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, bgPaint);
        rect.set(pad, cy - trackH / 2f, pad + usable * progress, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fgPaint);

        if (expand > 0.01f) {
            canvas.drawCircle(pad + usable * progress, cy, AndroidUtilities.lerp(0, dp(8), expand), knobPaint);
            if (delegate != null && delegate.getDurationMs() > 0) {
                int dur = delegate.getDurationMs();
                int cur = (int) (progress * dur);
                String text = format(cur) + " / " + format(dur);
                timePaint.setAlpha((int) (255 * expand));
                float tw = timePaint.measureText(text);
                canvas.drawText(text, (getWidth() - tw) / 2f, cy - dp(16), timePaint);
            }
        }
    }

    private static String format(int ms) {
        int totalSec = ms / 1000;
        return String.format(LocaleController.getInstance().getCurrentLocale(), "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
