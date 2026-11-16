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

    private LinearLayout bandsBox;
    private TextView status;
    private final TextView[] valueViews = new TextView[16];
    private final SeekBar[] sliders = new SeekBar[16];

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

    /** Смуги будуємо з живого ефекту: їх кількість і діапазон задає сама система. */
    private void rebuildBands() {
        bandsBox.removeAllViews();
        int n = EqEngine.bandCount();
        short[] r = EqEngine.bandRange();
        for (int i = 0; i < n && i < sliders.length; i++) {
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(this);
            int hz = EqEngine.centerFreq(idx);
            name.setText(hz >= 1000 ? String.format("%.1f кГц", hz / 1000f) : hz + " Гц");
            name.setTextColor(FG);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);

            TextView value = new TextView(this);
            value.setTextColor(ACCENT);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            value.setText(dbText(EqEngine.levelOf(idx)));
            head.addView(value);

            SeekBar bar = new SeekBar(this);
            bar.setMax(r[1] - r[0]);
            bar.setProgress(EqEngine.levelOf(idx) - r[0]);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) return;
                    short level = (short) (r[0] + p);
                    EqEngine.setLevel(idx, level);
                    value.setText(dbText(level));
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { }
            });

            row.addView(head);
            row.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            bandsBox.addView(row);
            sliders[idx] = bar;
            valueViews[idx] = value;
        }
    }

    private static String dbText(short level) {
        float db = level / 100f;                 // ефект працює в 1/100 дБ
        return String.format("%+.1f дБ", db);
    }

    private void refreshStatus() {
        if (status == null) return;
        if (EqEngine.isAlive()) {
            status.setText("Активний · " + EqEngine.bandCount() + " смуг · системний вихід");
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
