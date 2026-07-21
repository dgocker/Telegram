package org.telegram.ui.Components;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.MotionEvent;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;

/**
 * TextView, в котором веб-ссылки кликабельны (тап — открыть, долгое нажатие —
 * скопировать), а тап мимо ссылки пробрасывается в OnClickListener (закрытие).
 * Своё определение попадания в ссылку, без LinkMovementMethod (та ломает
 * onClick родителя и скролл).
 */
public class LinkSpanTextView extends TextView {

    private ClickableSpan pressedSpan;
    private boolean longPressed;
    private final Runnable longPressRunnable = () -> {
        if (pressedSpan instanceof URLSpan) {
            longPressed = true;
            copyLink(((URLSpan) pressedSpan).getURL());
        }
    };

    public LinkSpanTextView(Context context) {
        super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, BufferType.SPANNABLE);
        CharSequence current = getText();
        if (current instanceof Spannable) {
            Spannable spannable = (Spannable) current;
            // Linkify.addLinks стирает существующие URLSpan — если ссылки уже расставлены
            // (entities Telegram, включая скрытые text_url), не затираем их; серверные
            // entities покрывают и голые URL, так что Linkify тогда не нужен
            if (spannable.getSpans(0, spannable.length(), URLSpan.class).length == 0) {
                Linkify.addLinks(spannable, Linkify.WEB_URLS);
            }
        }
    }

    private ClickableSpan spanAt(MotionEvent event) {
        CharSequence text = getText();
        if (!(text instanceof Spanned) || getLayout() == null) {
            return null;
        }
        int x = (int) event.getX() - getTotalPaddingLeft() + getScrollX();
        int y = (int) event.getY() - getTotalPaddingTop() + getScrollY();
        int line = getLayout().getLineForVertical(y);
        if (x < getLayout().getLineLeft(line) || x > getLayout().getLineRight(line)) {
            return null;
        }
        int offset = getLayout().getOffsetForHorizontal(line, x);
        ClickableSpan[] spans = ((Spanned) text).getSpans(offset, offset, ClickableSpan.class);
        return spans.length > 0 ? spans[0] : null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedSpan = spanAt(event);
                longPressed = false;
                if (pressedSpan != null) {
                    AndroidUtilities.runOnUIThread(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout());
                }
                break;
            case MotionEvent.ACTION_UP:
                AndroidUtilities.cancelRunOnUIThread(longPressRunnable);
                if (pressedSpan != null && !longPressed) {
                    ClickableSpan span = pressedSpan;
                    pressedSpan = null;
                    if (span instanceof URLSpan) {
                        Browser.openUrl(getContext(), ((URLSpan) span).getURL());
                    } else {
                        span.onClick(this);
                    }
                    return true; // ссылку обработали — родителю (закрытию) не отдаём
                }
                pressedSpan = null;
                if (longPressed) {
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                AndroidUtilities.cancelRunOnUIThread(longPressRunnable);
                pressedSpan = null;
                break;
        }
        // мимо ссылки — обычная обработка (сработает OnClickListener = закрыть текст)
        return super.onTouchEvent(event);
    }

    private void copyLink(String url) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("label", url));
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            android.widget.Toast.makeText(getContext(), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.LinkCopied), android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
    }
}
