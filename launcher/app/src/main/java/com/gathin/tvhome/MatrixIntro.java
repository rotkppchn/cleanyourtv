package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * matrix_intro (theme config.json): digital rain when the TV wakes from standby and on the first Home of each boot.
 * Keys: matrix_intro (bool, off by default), matrix_intro_seconds (2..15, default 5),
 * matrix_intro_color ("#RRGGBB", default #D63C54), matrix_intro_entry_seconds (0..5, default 1.6),
 * matrix_intro_speed (fall speed factor 0.3..2, default 1).
 */
final class MatrixIntro {
    static final long FADE_MS = 700;
    private static final String TAG = "MatrixIntro";
    private static final String PREFS = "matrix_intro";
    private static final String BOOT_KEY = "boot";
    private static BroadcastReceiver screenOn;

    private MatrixIntro() {
    }

    static final class Settings {
        boolean enabled;
        float seconds = 5f;
        float entrySeconds = 1.6f;
        float speed = 1f;
        int color = 0xD63C54;
    }

    static Settings settings(Context ctx) {
        Settings s = new Settings();
        File dir = Config.dir(ctx);
        File file = dir != null ? new File(dir, Config.FILE_NAME) : null;
        if (file == null || !file.canRead()) return s;
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            s.enabled = json.optBoolean("matrix_intro", false);
            s.seconds = (float) Math.max(2, Math.min(15, json.optDouble("matrix_intro_seconds", s.seconds)));
            s.entrySeconds = (float) Math.max(0, Math.min(5, json.optDouble("matrix_intro_entry_seconds", s.entrySeconds)));
            s.speed = (float) Math.max(0.3, Math.min(2, json.optDouble("matrix_intro_speed", s.speed)));
            String c = json.optString("matrix_intro_color", "");
            if (!c.isEmpty()) s.color = Color.parseColor(c) & 0xFFFFFF;
        } catch (Exception e) {
            Log.w(TAG, "config.json: " + e);
        }
        return s;
    }

    /** Listen for the screen coming on, once per process. Called from Home's onCreate. */
    static void register(Context ctx) {
        if (screenOn != null || !settings(ctx).enabled) return;
        Context app = ctx.getApplicationContext();
        screenOn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Log.i(TAG, "screen on");
                if (settings(app).enabled) show(app);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(screenOn, f, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(screenOn, f);
        }
    }

    /** The first Home of a boot shows the intro once. True when it was started. */
    static boolean showOncePerBoot(Context ctx) {
        if (!settings(ctx).enabled) return false;
        int boot = OpenOnStart.boot(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (boot < 0 || prefs.getInt(BOOT_KEY, -1) == boot) return false;
        prefs.edit().putInt(BOOT_KEY, boot).commit();
        show(ctx);
        return true;
    }

    static void show(Context ctx) {
        try {
            ctx.startActivity(new Intent(ctx, MatrixIntroActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            Log.w(TAG, "start: " + e);
        }
    }
}
