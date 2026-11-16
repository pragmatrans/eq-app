package ua.pp.audioeq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Вертикальні фейдери — один в один як у Dolby Atmos, щоб не переучуватись:
 * значення в «чипі» над фейдером, сама ручка на треку, частота під ним.
 *
 * Малюємо самі, без сторонніх бібліотек: 14 фейдерів у ряд, заповнення від нульової лінії
 * вгору/вниз, торкання = рух ручки.
 */
public class FadersView extends View {

    private static final int BG = Color.parseColor("#0B0D10");
    private static final int TRACK = Color.parseColor("#1E3A3F");
    private static final int FILL = Color.parseColor("#A8D8DE");
    private static final int CHIP = Color.parseColor("#2A4A50");
    private static final int TXT = Color.parseColor("#E8EAED");
    private static final int THUMB = Color.parseColor("#8FB8BE");

    private static final float MIN_DB = -12f;
    private static final float MAX_DB = 12f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int activeIndex = -1;
    private Runnable onChanged;

    public FadersView(Context ctx) {
        super(ctx);
        trackPaint.setColor(TRACK);
        fillPaint.setColor(FILL);
        chipPaint.setColor(CHIP);
        thumbPaint.setColor(THUMB);
        textPaint.setColor(TXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setBackgroundColor(BG);
    }

    public void setOnChanged(Runnable r) { onChanged = r; }

    private float px(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    @Override protected void onDraw(Canvas c) {
        int n = EqEngine.bandCount();
        if (n <= 0) return;

        float gap = px(8);
        float slot = (getWidth() - gap * (n + 1)) / n;

        float top = px(76);                       // місце під чип зі значенням
        float bottom = getHeight() - px(52);      // місце під підпис частоти
        float zeroY = (top + bottom) / 2f;

        textPaint.setTextSize(px(15));
        float chipH = px(30);
        float labelSize = px(14);

        for (int i = 0; i < n; i++) {
            float cx = gap + slot * i + slot / 2f;
            float halfW = Math.min(slot * 0.30f, px(9));

            // трек
            rect.set(cx - halfW, top, cx + halfW, bottom);
            c.drawRoundRect(rect, halfW, halfW, trackPaint);

            // заповнення від нульової лінії до поточного значення
            float db = EqEngine.gainOf(i);
            float frac = (db - MIN_DB) / (MAX_DB - MIN_DB);           // 0..1
            float y = bottom - frac * (bottom - top);
            rect.set(cx - halfW, Math.min(y, zeroY), cx + halfW, Math.max(y, zeroY));
            c.drawRoundRect(rect, halfW, halfW, fillPaint);

            // ручка
            rect.set(cx - slot * 0.34f, y - px(4), cx + slot * 0.34f, y + px(4));
            c.drawRoundRect(rect, px(4), px(4), thumbPaint);

            // чип зі значенням
            String val = String.format("%.1f", db).replace('.', ',');
            float chipW = Math.max(textPaint.measureText(val) + px(20), slot * 0.8f);
            rect.set(cx - chipW / 2f, px(30), cx + chipW / 2f, px(30) + chipH);
            c.drawRoundRect(rect, chipH / 2f, chipH / 2f, chipPaint);
            textPaint.setTextSize(px(15));
            c.drawText(val, cx, px(30) + chipH * 0.68f, textPaint);

            // підпис частоти
            float hz = EqEngine.freqOf(i);
            String label = hz >= 1000 ? Math.round(hz / 1000f) + "k" : String.valueOf((int) hz);
            textPaint.setTextSize(labelSize);
            c.drawText(label, cx, getHeight() - px(24), textPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int n = EqEngine.bandCount();
        if (n <= 0) return false;
        float gap = px(8);
        float slot = (getWidth() - gap * (n + 1)) / n;
        float top = px(76);
        float bottom = getHeight() - px(52);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (activeIndex < 0) {
                    int idx = (int) ((e.getX() - gap) / slot);
                    if (idx < 0 || idx >= n) return false;
                    activeIndex = idx;
                    performClick();
                }
                // тягнемо смугу, на якій почали — як у справжніх фейдерах
                float frac = (bottom - e.getY()) / (bottom - top);
                frac = Math.max(0f, Math.min(1f, frac));
                float db = MIN_DB + frac * (MAX_DB - MIN_DB);
                EqEngine.setGain(activeIndex, db);
                invalidate();
                if (onChanged != null) onChanged.run();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeIndex = -1;
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
