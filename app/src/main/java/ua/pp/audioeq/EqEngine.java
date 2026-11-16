package ua.pp.audioeq;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Єдине місце, де живе ефект.
 *
 * Основний шлях — {@link DynamicsProcessing}: штатний фреймворковий ефект із параметричним
 * еквалайзером (у нашій прошивці оголошений як `dynamics_processing` / libdynproc.so).
 * Чому не {@link Equalizer}: його effectProxy (libbundlewrapper) створюється, але будь-який
 * запис рівня падає з «AudioEffect: set/get parameter error» — перевірено на живому пристрої.
 * Equalizer лишається запасним шляхом.
 *
 * Головна вимога — «не падає й не відвалюється»:
 *  - кожен виклик у try/catch, жодна помилка ефекту не вбиває процес;
 *  - живість перевіряється **подією, а не таймером**: коли починається звук, коли застосунок
 *    повертається на екран і коли ефект втрачає контроль. Ніяких періодичних записів — вони
 *    витрачають ресурс флеш-памʼяті й будять пристрій у сні;
 *  - рівні зберігаються і повторно застосовуються після кожного переприєднання.
 */
public final class EqEngine {

    private static final String TAG = "AudioEq";
    private static final String PREFS = "eq";
    private static final String KEY_LEVELS = "levels";
    private static final int BANDS = 6;
    /** Частоти смуг, Гц: від басу до повітря. */
    private static final float[] FREQS = {60f, 230f, 910f, 2300f, 5000f, 12000f};

    private static DynamicsProcessing dp;
    private static Equalizer eqFallback;
    private static SharedPreferences prefs;
    private static Handler handler;
    private static final float[] gains = new float[BANDS];   // дБ; 0 = пласко
    private static boolean wanted = true;
    private static String lastError = "";
    private static Runnable onChange;

    private EqEngine() {}

    static synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        loadLevels();
        attach();
        watchPlayback(ctx);
    }

    /**
     * Подієвий сторож: прокидаємось, коли почався звук.
     *
     * Саме тоді ефект і буває мертвим (після перезапуску audioserver) — а не уві сні планшета.
     * Тому жодного таймера: нуль записів, поки нічого не грає.
     */
    private static void watchPlayback(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            // Перезапуск audioserver зносить усі ефекти й пере-додає аудіопристрої — ця подія
            // і є сигналом «перевір живість». Та сама, що будить нас, коли встромили навушники.
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { verify(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { verify(); }
            }, handler);
            am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    // isActive() — прихований API (падає на targetSdk 34), тому не фільтруємо за
                    // станом: будь-яка зміна в списку програвачів = привід один раз перевірити
                    // живість. Подія рідка, запис один — флеш не турбуємо.
                    if (!configs.isEmpty()) verify();
                }
            }, handler);
        } catch (Throwable t) {
            Log.w(TAG, "watchPlayback: " + t);
        }
    }

    private static void attach() {
        releaseQuietly();
        if (attachDynamics()) return;
        attachEqualizer();
    }

    private static boolean attachDynamics() {
        try {
            DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,          // вхідних каналів
                    true,       // preEq
                    BANDS,
                    false, 0,   // mbc
                    false, 0,   // postEq
                    false);     // limiter
            DynamicsProcessing candidate = new DynamicsProcessing(0, 0, b.build());
            try {
                candidate.setControlStatusListener((effect, control) -> {
                    if (!control) {               // хтось забрав контроль — вертаємо собі
                        releaseQuietly();
                        attach();
                        notifyChanged();
                    }
                });
            } catch (Throwable ignored) { }
            candidate.setEnabled(wanted);
            applyDynamics(candidate);
            dp = candidate;
            lastError = "";
            Log.i(TAG, "DynamicsProcessing приєднано");
            return true;
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            Log.w(TAG, "DynamicsProcessing недоступний: " + lastError);
            return false;
        }
    }

    private static void attachEqualizer() {
        try {
            eqFallback = new Equalizer(0, 0);
            eqFallback.setEnabled(wanted);
            applyFallback();
            lastError = "";
            Log.i(TAG, "Equalizer приєднано (запасний шлях)");
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            Log.w(TAG, "Equalizer теж недоступний: " + lastError);
            releaseQuietly();
        }
    }

    private static void applyDynamics(DynamicsProcessing fx) {
        for (int i = 0; i < BANDS; i++) {
            DynamicsProcessing.EqBand band =
                    new DynamicsProcessing.EqBand(true, FREQS[i], clampDb(gains[i]));
            fx.setPreEqBandAllChannelsTo(i, band);
        }
    }

    private static void applyFallback() {
        if (eqFallback == null) return;
        try {
            short[] r = eqFallback.getBandLevelRange();      // у мілібелах
            short usable = eqFallback.getNumberOfBands();
            for (short i = 0; i < usable; i++) {
                int mb = (int) (gains[Math.min(i, BANDS - 1)] * 100);
                if (mb < r[0]) mb = r[0];
                if (mb > r[1]) mb = r[1];
                eqFallback.setBandLevel(i, (short) mb);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyFallback: " + t);
        }
    }

    private static float clampDb(float db) {
        if (db < -12f) return -12f;      // межі EqBand у фреймворку
        if (db > 12f) return 12f;
        return db;
    }

    /**
     * Перевірка живності — ОДИН запис, і лише по події.
     *
     * Після перезапуску audioserver хендл мертвий, але `getEnabled()` цього не показує: повертає
     * закешоване значення. Падає тільки запис — тому пробуємо записати смуги й на відмові
     * переприєднуємось. Викликається з onResume, з початку відтворення і з втрати контролю.
     */
    static synchronized void verify() {
        try {
            if (dp == null && eqFallback == null) {
                attach();
                notifyChanged();
                return;
            }
            if (wanted) {
                if (dp != null) dp.setEnabled(true);
                else eqFallback.setEnabled(true);
            }
            applyLevelsToLive();
        } catch (Throwable t) {
            Log.w(TAG, "ефект мертвий, переприєдную: " + t);
            releaseQuietly();
            attach();
            notifyChanged();
        }
    }

    /** Записати поточні рівні в живий ефект (без перевірок живності). */
    private static void applyLevelsToLive() {
        if (dp != null) applyDynamics(dp);
        else applyFallback();
    }

    // ---- рівні ----

    private static void loadLevels() {
        String s = prefs.getString(KEY_LEVELS, "");
        try {
            String[] parts = s.split(",");
            if (parts.length == BANDS) {
                for (int i = 0; i < BANDS; i++) gains[i] = clampDb(Float.parseFloat(parts[i].trim()));
            }
        } catch (Throwable ignored) { }
    }

    private static void saveLevels() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BANDS; i++) {
            if (i > 0) sb.append(',');
            sb.append(gains[i]);
        }
        prefs.edit().putString(KEY_LEVELS, sb.toString()).apply();
    }

    // ---- API для UI ----

    static int bandCount() { return BANDS; }

    static float freqOf(int i) { return FREQS[i]; }

    static synchronized float gainOf(int i) { return gains[i]; }

    static synchronized void setGain(int i, float db) {
        if (i < 0 || i >= BANDS) return;
        gains[i] = clampDb(db);
        try {
            if (dp != null) applyDynamics(dp);
            else applyFallback();
        } catch (Throwable t) {
            Log.w(TAG, "setGain: " + t);
        }
        saveLevels();
    }

    static synchronized void setFlat() {
        for (int i = 0; i < BANDS; i++) gains[i] = 0f;
        try {
            if (dp != null) applyDynamics(dp);
            else applyFallback();
        } catch (Throwable t) {
            Log.w(TAG, "setFlat: " + t);
        }
        saveLevels();
    }

    static synchronized boolean isWanted() { return wanted; }

    static synchronized void setWanted(boolean v) {
        wanted = v;
        try {
            if (dp != null) dp.setEnabled(v);
            else if (eqFallback != null) eqFallback.setEnabled(v);
        } catch (Throwable t) {
            Log.w(TAG, "setWanted: " + t);
        }
    }

    static synchronized boolean isAlive() { return dp != null || eqFallback != null; }

    static synchronized String engineName() {
        if (dp != null) return "параметричний";
        if (eqFallback != null) return "смуговий";
        return "немає";
    }

    static synchronized String lastError() { return lastError; }

    static void setOnChange(Runnable r) { onChange = r; }

    private static void notifyChanged() {
        Runnable r = onChange;
        if (r != null && handler != null) handler.post(r);
    }

    private static void releaseQuietly() {
        try { if (dp != null) dp.release(); } catch (Throwable ignored) { }
        try { if (eqFallback != null) eqFallback.release(); } catch (Throwable ignored) { }
        dp = null;
        eqFallback = null;
    }
}
