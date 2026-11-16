package ua.pp.audioeq;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Єдине місце, де живе ефект еквалайзера.
 *
 * Чому так, а не «створити в Activity»: ефект привʼязаний до процесу. Тримаємо його в
 * Application, щоб він жив, поки живе процес, і не зникав, коли активність іде у фон.
 *
 * Головна вимога — «не відвалюється». Тому:
 *  - кожен виклик у try/catch: жодна помилка ефекту не може вбити застосунок;
 *  - сторож кожні 5 с перевіряє, що ефект справді приєднаний і ввімкнений, і переприєднує його
 *    після перезапуску audioserver / втрати контролю (саме так ефекти й «відвалюються»);
 *  - рівні смуг зберігаються і повторно застосовуються після кожного переприєднання.
 */
public final class EqEngine {

    private static final String TAG = "AudioEq";
    private static final String PREFS = "eq";
    private static final String KEY_LEVELS = "levels";
    private static final long WATCHDOG_MS = 5000;

    private static Equalizer eq;
    private static SharedPreferences prefs;
    private static Handler handler;
    private static short[] levels;          // у одиницях діапазону ефекту, не дБ
    private static short[] range = {-1500, 1500};
    private static short bands = 5;
    private static boolean wanted = true;   // користувач хоче, щоб ефект був увімкнений
    private static String lastError = "";
    private static Runnable onChange;

    private EqEngine() {}

    static synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        attach(ctx);
        scheduleWatchdog(ctx.getApplicationContext());
    }

    /** Приєднатись (або переприєднатись) до глобального вихідного мікшера. */
    private static void attach(Context ctx) {
        try {
            if (eq != null) return;
            eq = new Equalizer(0, 0);           // session 0 = весь вихід пристрою
            bands = eq.getNumberOfBands();
            short[] r = eq.getBandLevelRange();
            if (r != null && r.length >= 2) range = new short[]{r[0], r[1]};
            loadLevels();
            eq.setEnabled(wanted);
            applyLevels();
            lastError = "";
        } catch (Throwable t) {                 // ефект можуть не дати — це не привід падати
            lastError = String.valueOf(t.getMessage());
            Log.w(TAG, "attach failed: " + lastError);
            releaseQuietly();
        }
    }

    private static void scheduleWatchdog(Context appCtx) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                watchdogTick(appCtx);
                handler.postDelayed(this, WATCHDOG_MS);
            }
        }, WATCHDOG_MS);
    }

    private static synchronized void watchdogTick(Context appCtx) {
        try {
            if (eq == null) {
                attach(appCtx);                 // audioserver перезапустився — ефект зник
                notifyChanged();
                return;
            }
            if (wanted && !eq.getEnabled()) {   // «заснув» або втратив контроль
                eq.setEnabled(true);
                applyLevels();
                notifyChanged();
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchdog: " + t);
            releaseQuietly();
            attach(appCtx);
            notifyChanged();
        }
    }

    // ---- рівні ----

    private static void loadLevels() {
        String s = prefs.getString(KEY_LEVELS, "");
        short[] out = new short[bands];
        try {
            String[] parts = s.split(",");
            if (parts.length == bands) {
                for (int i = 0; i < bands; i++) out[i] = Short.parseShort(parts[i].trim());
                levels = out;
                return;
            }
        } catch (Throwable ignored) { }
        levels = out;                            // типово — пласка АЧХ
    }

    private static void saveLevels() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(levels[i]);
        }
        prefs.edit().putString(KEY_LEVELS, sb.toString()).apply();
    }

    private static void applyLevels() {
        if (eq == null || levels == null) return;
        try {
            short usable = eq.getNumberOfBands();
            for (short i = 0; i < usable && i < levels.length; i++) {
                eq.setBandLevel(i, clamp(levels[i]));
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyLevels: " + t);
        }
    }

    private static short clamp(short v) {
        if (v < range[0]) return range[0];
        if (v > range[1]) return range[1];
        return v;
    }

    // ---- API для UI ----

    static synchronized int bandCount() { return bands; }

    static synchronized short[] bandRange() { return range; }

    static synchronized short levelOf(int i) {
        if (levels == null) return 0;
        return i < levels.length ? levels[i] : 0;
    }

    static synchronized int centerFreq(int i) {
        try { return eq == null ? 0 : eq.getCenterFreq((short) i) / 1000; }
        catch (Throwable t) { return 0; }
    }

    static synchronized void setLevel(int i, short v) {
        if (levels == null || i < 0 || i >= levels.length) return;
        levels[i] = clamp(v);
        try {
            if (eq != null) eq.setBandLevel((short) i, levels[i]);
        } catch (Throwable t) {
            Log.w(TAG, "setLevel: " + t);
        }
        saveLevels();
    }

    static synchronized void setFlat() {
        if (levels == null) return;
        for (int i = 0; i < levels.length; i++) levels[i] = 0;
        applyLevels();
        saveLevels();
    }

    static synchronized boolean isWanted() { return wanted; }

    static synchronized void setWanted(boolean v) {
        wanted = v;
        try { if (eq != null) eq.setEnabled(v); }
        catch (Throwable t) { Log.w(TAG, "setWanted: " + t); }
    }

    static synchronized boolean isAlive() { 
        try { return eq != null; } catch (Throwable t) { return false; }
    }

    static synchronized String lastError() { return lastError; }

    static void setOnChange(Runnable r) { onChange = r; }

    private static void notifyChanged() {
        Runnable r = onChange;
        if (r != null) handler.post(r);
    }

    private static void releaseQuietly() {
        try { if (eq != null) eq.release(); } catch (Throwable ignored) { }
        eq = null;
    }
}
