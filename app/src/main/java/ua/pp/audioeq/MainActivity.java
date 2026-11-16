package ua.pp.audioeq;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Одна активність, нічого зайвого: смуги еквалайзера на весь екран, великий шрифт,
 * темний фон. Головне — читабельність і передбачуваність, а не «дизайн».
 */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0D10");
    private static final int FG = Color.parseColor("#E8EAED");
    private static final int ACCENT = Color.parseColor("#2FA360");
    private static final int DIM = Color.parseColor("#8A9099");

    /** Межі смуг: -12…+12 дБ з кроком 0.1 дБ — стільки дає EqBand у фреймворку. */
    private static final float MIN_DB = -12f;
    private static final float MAX_DB = 12f;
    private static final int STEPS = (int) ((MAX_DB - MIN_DB) * 10);

    private LinearLayout bandsBox;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        EqEngine.init(getApplicationContext());
        setContentView(buildUi());
        EqEngine.setOnChange(this::refreshStatus);
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        rebuildBands();      // ефект міг переприєднатись, поки ми були у фоні
        refreshStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Еквалайзер");
        title.setTextColor(FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, dp(6), 0, dp(14));
        root.addView(status);

        Switch onOff = new Switch(this);
        onOff.setText("Увімкнено");
        onOff.setTextColor(FG);
        onOff.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        onOff.setChecked(EqEngine.isWanted());
        onOff.setOnCheckedChangeListener((v, checked) -> {
            EqEngine.setWanted(checked);
            refreshStatus();
        });
        root.addView(onOff);

        bandsBox = new LinearLayout(this);
        bandsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bandsBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button flat = new Button(this);
        flat.setText("Пласко");
        flat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        flat.setOnClickListener(v -> { EqEngine.setFlat(); rebuildBands(); });
        root.addView(flat);

        return root;
    }

    /** Смуги малюємо зі стану рушія: частоти фіксовані, значення — з памʼяті. */
    private void rebuildBands() {
        bandsBox.removeAllViews();
        int n = EqEngine.bandCount();
        for (int i = 0; i < n; i++) {
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(this);
            float hz = EqEngine.freqOf(idx);
            name.setText(hz >= 1000 ? String.format("%.1f кГц", hz / 1000f) : (int) hz + " Гц");
            name.setTextColor(FG);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);

            TextView value = new TextView(this);
            value.setTextColor(ACCENT);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            value.setText(dbText(EqEngine.gainOf(idx)));
            head.addView(value);

            SeekBar bar = new SeekBar(this);
            bar.setMax(STEPS);
            bar.setProgress(toProgress(EqEngine.gainOf(idx)));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) return;
                    float db = fromProgress(p);
                    EqEngine.setGain(idx, db);
                    value.setText(dbText(db));
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { }
            });

            row.addView(head);
            row.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            bandsBox.addView(row);
        }
    }

    private static int toProgress(float db) {
        return Math.round((db - MIN_DB) * 10);
    }

    private static float fromProgress(int p) {
        return MIN_DB + p / 10f;
    }

    private static String dbText(float db) {
        return String.format("%+.1f дБ", db);
    }

    private void refreshStatus() {
        if (status == null) return;
        if (EqEngine.isAlive()) {
            status.setText("Активний · " + EqEngine.engineName() + " · системний вихід");
            status.setTextColor(DIM);
        } else {
            String err = EqEngine.lastError();
            status.setText("Немає доступу до ефекту" + (err.isEmpty() ? "" : ": " + err)
                    + " — повторюю спробу");
            status.setTextColor(Color.parseColor("#D24B45"));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
