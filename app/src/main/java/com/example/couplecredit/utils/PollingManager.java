package com.example.couplecredit.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class PollingManager {
    private static final String TAG = "PollingManager";
    private static final long INTERVAL_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable currentRefresh;
    private boolean running = false;

    private static final PollingManager INSTANCE = new PollingManager();

    public static PollingManager getInstance() {
        return INSTANCE;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (currentRefresh != null) {
                try {
                    currentRefresh.run();
                } catch (Exception e) {
                    Log.e(TAG, "Polling refresh error", e);
                }
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public void setRefreshAction(Runnable action) {
        currentRefresh = action;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, INTERVAL_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        // 释放注入的 refresh action（它捕获了 Activity 引用，不释放会泄漏 Activity）
        currentRefresh = null;
    }

}
