package com.example.couplecredit;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.utils.NotificationHelper;
import com.example.couplecredit.utils.UserInfoManager;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token.substring(0, Math.min(token.length(), 20)) + "...");
        NotificationHelper.registerFcmToken(this, token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);
        String title = null;
        String body = null;
        if (message.getNotification() != null) {
            title = message.getNotification().getTitle();
            body = message.getNotification().getBody();
        }
        if (title == null) title = "待办提醒";
        if (body == null) body = "你有新的待办提醒";

        int notificationId = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("navigate_to", "todo");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        NotificationHelper.showNotification(this, notificationId, title, body, intent);
    }
}
