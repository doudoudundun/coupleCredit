package com.example.couplecredit.utils;

import android.content.Context;
import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * 聊天背景更新管理器
 * 负责管理聊天背景更新的广播通知
 */
public class BackgroundUpdateManager {
    
    public static final String ACTION_BACKGROUND_UPDATED = "com.example.couplecredit.BACKGROUND_UPDATED";
    public static final String EXTRA_BACKGROUND_TYPE = "background_type"; // "resource" 或 "uri"
    public static final String EXTRA_BACKGROUND_VALUE = "background_value"; // 资源ID或URI字符串
    
    /**
     * 发送背景更新广播 - 预设背景
     * @param context 上下文
     * @param backgroundResId 背景资源ID
     */
    public static void notifyBackgroundUpdated(Context context, int backgroundResId) {
        Intent intent = new Intent(ACTION_BACKGROUND_UPDATED);
        intent.putExtra(EXTRA_BACKGROUND_TYPE, "resource");
        intent.putExtra(EXTRA_BACKGROUND_VALUE, String.valueOf(backgroundResId));
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
    
    /**
     * 发送背景更新广播 - 自定义背景
     * @param context 上下文
     * @param backgroundUri 背景图片URI
     */
    public static void notifyBackgroundUpdated(Context context, String backgroundUri) {
        Intent intent = new Intent(ACTION_BACKGROUND_UPDATED);
        intent.putExtra(EXTRA_BACKGROUND_TYPE, "uri");
        intent.putExtra(EXTRA_BACKGROUND_VALUE, backgroundUri);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
}