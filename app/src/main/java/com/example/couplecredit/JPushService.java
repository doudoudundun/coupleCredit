package com.example.couplecredit;

import android.content.Context;
import android.util.Log;

import com.example.couplecredit.utils.NotificationHelper;

import cn.jpush.android.api.JPushMessage;
import cn.jpush.android.service.JPushMessageReceiver;

public class JPushService extends JPushMessageReceiver {

    private static final String TAG = "JPushService";

    @Override
    public void onRegister(Context context, String registrationId) {
        Log.d(TAG, "JPush registered, regId=" + registrationId);
        NotificationHelper.registerPushToken(context, registrationId, "jpush");
    }

    @Override
    public void onTagOperatorResult(Context context, JPushMessage jPushMessage) {
        Log.d(TAG, "onTagOperatorResult: " + jPushMessage.getErrorCode());
    }

    @Override
    public void onAliasOperatorResult(Context context, JPushMessage jPushMessage) {
        Log.d(TAG, "onAliasOperatorResult: " + jPushMessage.getErrorCode());
    }
}
