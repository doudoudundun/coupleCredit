package com.example.couplecredit;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import com.example.couplecredit.utils.NotificationHelper;
import com.example.couplecredit.utils.UserInfoManager;

import cn.jpush.android.api.JPushInterface;

public class MyApplication extends MultiDexApplication {
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
        initJPush();
        initFcmToken();
    }

    private void initJPush() {
        try {
            JPushInterface.setDebugMode(true);
            JPushInterface.init(this);
            Log.d(TAG, "JPush initialized");

            if (UserInfoManager.isUserLoggedIn(this)) {
                String regId = JPushInterface.getRegistrationID(this);
                if (regId != null && !regId.isEmpty()) {
                    Log.d(TAG, "JPush regId: " + regId);
                    NotificationHelper.registerPushToken(this, regId, "jpush");
                }
            }

            // 延迟再试一次，等待 InitProvider 完成初始化
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (!UserInfoManager.isUserLoggedIn(this)) return;
                    String regId = JPushInterface.getRegistrationID(this);
                    if (regId != null && !regId.isEmpty()) {
                        Log.d(TAG, "JPush regId delayed: " + regId);
                        NotificationHelper.registerPushToken(this, regId, "jpush");
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "JPush delayed check failed: " + e.getMessage());
                }
            }, 15000);
        } catch (Throwable e) {
            Log.e(TAG, "JPush setup failed: " + e.getMessage());
        }
    }

    private void initFcmToken() {
        if (!UserInfoManager.isUserLoggedIn(this)) return;
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            Log.d(TAG, "FCM token obtained successfully");
                            NotificationHelper.registerFcmToken(this, task.getResult());
                        } else {
                            Exception ex = task.getException();
                            Log.e(TAG, "FCM token failed: " + (ex != null ? ex.getMessage() : "null"));
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Firebase init failed: " + e.getMessage());
        }
    }
}
