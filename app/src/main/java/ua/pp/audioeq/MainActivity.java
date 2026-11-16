package ua.pp.audioeq;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Один екран: заголовок, перемикач, вертикальні фейдери (як у Dolby Atmos) і «Пласко».
 * Ніяких списків, вкладок і налаштувань — щоб не переучуватись.
 */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0D10");
    private static final int FG = Color.parseColor("#E8EAED");
    private static final int DIM = Color.parseColor("#8A9099");

    private FadersView faders;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        EqEngine.init(getApplicationContext());
        setContentView(buildUi());
        EqEngine.setOnChange(this::refresh);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        EqEngine.verify();     // одноразова проба живності на поверненні в застосунок
        if (faders != null) faders.invalidate();
        refresh();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Еквалайзер");
        title.setTextColor(FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        Switch onOff = new Switch(this);
        onOff.setText("Увімкнено");
        onOff.setTextColor(FG);
        onOff.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        onOff.setChecked(EqEngine.isWanted());
        onOff.setOnCheckedChangeListener((v, checked) -> {
            EqEngine.setWanted(checked);
            refresh();
        });
        head.addView(onOff);
        root.addView(head);

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(status);

        faders = new FadersView(this);
        faders.setOnChanged(this::refresh);
        root.addView(faders, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button flat = new Button(this);
        flat.setText("Пласко");
        flat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        flat.setOnClickListener(v -> {
            EqEngine.setFlat();
            faders.invalidate();
        });
        root.addView(flat);

        return root;
    }

    private void refresh() {
        if (status == null) return;
        if (EqEngine.isAlive()) {
            status.setText("Активний · " + EqEngine.engineName() + " · " + EqEngine.bandCount() + " смуг");
            status.setTextColor(DIM);
        } else {
            String err = EqEngine.lastError();
            status.setText("Немає доступу до ефекту" + (err.isEmpty() ? "" : ": " + err)
                    + " — виправлю при першій же дії");
            status.setTextColor(Color.parseColor("#D24B45"));
        }
    }
}
