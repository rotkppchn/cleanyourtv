package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

/**
 * The matrix_intro screen: digital rain for a few seconds when the TV wakes or boots, then Home. Any key skips it.
 */
public class MatrixIntroActivity extends Activity {
    private static final String TAG = "MatrixIntro";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable done = this::toHome;
    private MatrixRainView rain;
    private boolean leaving;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MatrixIntro.Settings s = MatrixIntro.settings(this);
        rain = new MatrixRainView(this);
        rain.setColor(s.color);
        rain.setEntrySeconds(s.entrySeconds);
        rain.setSpeed(s.speed);
        setContentView(rain);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        handler.postDelayed(done, Math.max(1000L, (long) (s.seconds * 1000f) - MatrixIntro.FADE_MS));
        Log.i(TAG, "start, " + s.seconds + " s");
    }

    @Override
    protected void onResume() {
        super.onResume();
        rain.onResume();
    }

    /** Fade the rain out, then open Home behind it. */
    private void toHome() {
        if (leaving) return;
        leaving = true;
        handler.removeCallbacks(done);
        rain.fadeOut(MatrixIntro.FADE_MS);
        handler.postDelayed(() -> {
            Log.i(TAG, "end, " + rain.stats());
            Intent home = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(home);
            } catch (RuntimeException ignored) {
                // Home is the default launcher anyway; finishing shows whatever is behind
            }
            finish();
        }, MatrixIntro.FADE_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) toHome();
        return true;
    }

    @Override
    protected void onPause() {
        rain.onPause();
        super.onPause();
        // Another app took the screen (the TV's own wake-up input, say): don't come back later.
        if (!leaving) {
            Log.i(TAG, "paused early, " + rain.stats());
            handler.removeCallbacks(done);
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
