package com.example.couplecredit.function;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.couplecredit.R;

/**
 * 自定义Toast工具类
 * 提供统一的Toast样式和显示方法
 */
public class CustomToast {
    
    // Toast类型常量
    public static final int TYPE_SUCCESS = 1;
    public static final int TYPE_ERROR = 2;
    public static final int TYPE_WARNING = 3;
    public static final int TYPE_INFO = 4;
    public static final int TYPE_NORMAL = 5;
    
    /**
     * 显示普通Toast
     * @param context 上下文
     * @param message 消息内容
     */
    public static void show(Context context, String message) {
        show(context, message, TYPE_NORMAL, Toast.LENGTH_SHORT);
    }
    
    /**
     * 显示普通Toast（指定时长）
     * @param context 上下文
     * @param message 消息内容
     * @param duration 显示时长
     */
    public static void show(Context context, String message, int duration) {
        show(context, message, TYPE_NORMAL, duration);
    }
    
    /**
     * 显示成功Toast
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showSuccess(Context context, String message) {
        show(context, message, TYPE_SUCCESS, Toast.LENGTH_SHORT);
    }
    
    /**
     * 显示错误Toast
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showError(Context context, String message) {
        show(context, message, TYPE_ERROR, Toast.LENGTH_SHORT);
    }
    
    /**
     * 显示警告Toast
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showWarning(Context context, String message) {
        show(context, message, TYPE_WARNING, Toast.LENGTH_SHORT);
    }
    
    /**
     * 显示信息Toast
     * @param context 上下文
     * @param message 消息内容
     */
    public static void showInfo(Context context, String message) {
        show(context, message, TYPE_INFO, Toast.LENGTH_SHORT);
    }
    
    /**
     * 显示自定义Toast的核心方法
     * @param context 上下文
     * @param message 消息内容
     * @param type Toast类型
     * @param duration 显示时长
     */
    public static void show(Context context, String message, int type, int duration) {
        try {
            // 创建Toast对象
            Toast toast = new Toast(context);
            
            // 加载自定义布局
            LayoutInflater inflater = LayoutInflater.from(context);
            View toastView = inflater.inflate(R.layout.custom_toast_layout, null);
            
            // 获取视图组件
            ImageView ivIcon = toastView.findViewById(R.id.iv_toast_icon);
            TextView tvMessage = toastView.findViewById(R.id.tv_toast_message);
            
            // 设置消息文本
            tvMessage.setText(message);
            
            // 根据类型设置图标和样式
            switch (type) {
                case TYPE_SUCCESS:
                    ivIcon.setImageResource(R.drawable.ic_favorite);
                    ivIcon.setVisibility(View.VISIBLE);
                    tvMessage.setTextColor(context.getResources().getColor(android.R.color.holo_green_dark));
                    break;
                case TYPE_ERROR:
                    ivIcon.setImageResource(R.drawable.ic_favorite);
                    ivIcon.setVisibility(View.VISIBLE);
                    tvMessage.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                    break;
                case TYPE_WARNING:
                    ivIcon.setImageResource(R.drawable.ic_favorite);
                    ivIcon.setVisibility(View.VISIBLE);
                    tvMessage.setTextColor(context.getResources().getColor(android.R.color.holo_orange_dark));
                    break;
                case TYPE_INFO:
                    ivIcon.setImageResource(R.drawable.ic_favorite);
                    ivIcon.setVisibility(View.VISIBLE);
                    tvMessage.setTextColor(context.getResources().getColor(android.R.color.holo_blue_dark));
                    break;
                case TYPE_NORMAL:
                default:
                    ivIcon.setVisibility(View.GONE);
                    tvMessage.setTextColor(context.getResources().getColor(android.R.color.black));
                    break;
            }
            
            // 设置Toast视图和位置
            toast.setView(toastView);
            toast.setDuration(duration);
            toast.setGravity(Gravity.BOTTOM, 0, 250);
            
            // 显示Toast
            toast.show();
            
        } catch (Exception e) {
            // 如果自定义Toast失败，使用系统默认Toast
            Toast.makeText(context, message, duration).show();
        }
    }
}