package ua.pp.audioeq;

import android.app.Application;

/** Тримає ефект у процесі: він має жити, навіть коли екран активності пішов у фон. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        EqEngine.init(this);
    }
}
