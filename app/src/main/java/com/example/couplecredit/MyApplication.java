package com.example.couplecredit;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import com.example.couplecredit.utils.NotificationHelper;
import com.example.couplecredit.utils.UserInfoManager;

public class MyApplication extends MultiDexApplication {
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
        initFcmToken();
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
