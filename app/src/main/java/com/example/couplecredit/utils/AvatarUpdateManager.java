package com.example.couplecredit.utils;

import android.content.Context;
import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * 头像更新管理器
 * 负责管理头像更新的广播通知
 */
public class AvatarUpdateManager {
    
    public static final String ACTION_AVATAR_UPDATED = "com.example.couplecredit.AVATAR_UPDATED";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_AVATAR_URI = "avatar_uri";
    
    /**
     * 发送头像更新广播
     * @param context 上下文
     * @param userId 用户ID
     * @param avatarUri 新头像URI
     */
    public static void notifyAvatarUpdated(Context context, String userId, String avatarUri) {
        Intent intent = new Intent(ACTION_AVATAR_UPDATED);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_AVATAR_URI, avatarUri);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
}