package com.example.couplecredit.manager;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import com.example.couplecredit.R;
import com.example.couplecredit.model.ChatMessage;

/**
 * 消息弹出菜单管理器
 * 负责管理聊天消息的长按弹出菜单功能
 * 
 * 功能特性：
 * - 智能定位：根据消息位置和屏幕边界自动调整菜单位置
 * - 居中显示：优先在消息框中心显示菜单
 * - 防溢出：自动检测并防止菜单溢出屏幕边界
 * - 内侧对齐：短消息时根据发送者调整为内侧显示
 * - 菜单功能：支持复制、删除、记账等操作
 */
public class MessagePopupManager {
    
    private Context context;
    private OnMenuActionListener listener;
    
    /**
     * 菜单操作监听器接口
     * 定义弹出菜单各项操作的回调方法
     */
    public interface OnMenuActionListener {
        /**
         * 复制消息回调
         * @param message 要复制的消息
         */
        void onCopyMessage(ChatMessage message);
        
        /**
         * 删除消息回调
         * @param message 要删除的消息
         * @param position 消息位置
         */
        void onDeleteMessage(ChatMessage message, int position);
        
        /**
         * 记账功能回调
         * @param message 相关消息
         */
        void onBillingAction(ChatMessage message);
    }
    
    /**
     * 构造函数
     * @param context 上下文对象
     */
    public MessagePopupManager(Context context) {
        this.context = context;
    }
    
    /**
     * 设置菜单操作监听器
     * @param listener 监听器实例
     */
    public void setOnMenuActionListener(OnMenuActionListener listener) {
        this.listener = listener;
    }
    
    /**
     * 显示消息弹出菜单
     * 实现智能定位算法，确保菜单显示在合适的位置
     * 
     * @param anchorView 锚点视图（消息项视图）
     * @param message 消息对象
     * @param position 消息在列表中的位置
     */
    public void showMessagePopupMenu(View anchorView, ChatMessage message, int position) {
        // 创建PopupWindow
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_message_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        
        // 设置弹出窗口样式
        setupPopupWindowStyle(popupWindow);
        
        // 设置菜单项点击事件
        setupMenuItemClickListeners(popupView, popupWindow, message, position);
        
        // 计算并设置弹出位置
        calculateAndShowPopup(popupWindow, popupView, anchorView, message);
    }
    
    /**
     * 设置PopupWindow的样式属性
     * @param popupWindow PopupWindow实例
     */
    private void setupPopupWindowStyle(PopupWindow popupWindow) {
        // 设置背景和动画效果
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setElevation(8); // 设置阴影效果
        popupWindow.setFocusable(true); // 设置可获取焦点
        popupWindow.setOutsideTouchable(true); // 点击外部可关闭
    }
    
    /**
     * 设置菜单项的点击事件监听器
     * @param popupView 弹出菜单视图
     * @param popupWindow PopupWindow实例
     * @param message 消息对象
     * @param position 消息位置
     */
    private void setupMenuItemClickListeners(View popupView, PopupWindow popupWindow, 
                                            ChatMessage message, int position) {
        // 获取菜单项
        LinearLayout tvCopy = popupView.findViewById(R.id.tv_copy);
        LinearLayout tvDelete = popupView.findViewById(R.id.tv_delete);
        LinearLayout tvBilling = popupView.findViewById(R.id.tv_billing);
        
        // 复制消息
        tvCopy.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCopyMessage(message);
            } else {
                // 默认复制行为
                copyMessageToClipboard(message);
            }
            popupWindow.dismiss();
        });
        
        // 删除消息
        tvDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteMessage(message, position);
            }
            popupWindow.dismiss();
        });
        
        // 记账功能
        tvBilling.setOnClickListener(v -> {
            if (listener != null) {
                listener.onBillingAction(message);
            } else {
                // 默认提示
                // Toast.makeText(context, "记账功能开发中...", Toast.LENGTH_SHORT).show();
            }
            popupWindow.dismiss();
        });
    }
    
    /**
     * 计算弹出菜单的最佳显示位置并显示
     * 实现智能定位算法：
     * 1. 优先居中显示
     * 2. 防止左右溢出屏幕
     * 3. 短消息时根据发送者调整为内侧显示
     * 4. 垂直方向显示在消息上方
     * 
     * @param popupWindow PopupWindow实例
     * @param popupView 弹出菜单视图
     * @param anchorView 锚点视图
     * @param message 消息对象
     */
    private void calculateAndShowPopup(PopupWindow popupWindow, View popupView, 
                                     View anchorView, ChatMessage message) {
        // 测量弹出窗口的尺寸
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = popupView.getMeasuredWidth();
        int popupHeight = popupView.getMeasuredHeight();
        
        // 获取屏幕宽度和消息框在屏幕中的位置
        int[] anchorLocation = new int[2];
        anchorView.getLocationOnScreen(anchorLocation);
        int anchorScreenX = anchorLocation[0];
        int anchorWidth = anchorView.getWidth();
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        
        // 计算智能的水平偏移量
        int xOffset = calculateHorizontalOffset(anchorScreenX, anchorWidth, popupWidth, 
                                               screenWidth, message);
        
        // 计算垂直偏移量，让弹出菜单显示在消息上方且不覆盖
        int yOffset = calculateVerticalOffset(anchorView.getHeight(), popupHeight);
        
        // 显示弹出窗口
        popupWindow.showAsDropDown(anchorView, xOffset, yOffset);
    }
    
    /**
     * 计算水平方向的偏移量
     * 实现智能水平定位算法
     * 
     * @param anchorScreenX 锚点视图在屏幕中的X坐标
     * @param anchorWidth 锚点视图宽度
     * @param popupWidth 弹出菜单宽度
     * @param screenWidth 屏幕宽度
     * @param message 消息对象
     * @return 水平偏移量
     */
    private int calculateHorizontalOffset(int anchorScreenX, int anchorWidth, int popupWidth, 
                                        int screenWidth, ChatMessage message) {
        // 首先尝试居中显示
        int centerOffset = (anchorWidth - popupWidth) / 2;
        int popupLeftEdge = anchorScreenX + centerOffset;
        int popupRightEdge = popupLeftEdge + popupWidth;
        
        int xOffset;
        
        // 检查是否会溢出屏幕
        if (popupLeftEdge < 0) {
            // 左侧溢出，贴近左边界
            xOffset = -anchorScreenX;
        } else if (popupRightEdge > screenWidth) {
            // 右侧溢出，贴近右边界
            xOffset = screenWidth - anchorScreenX - popupWidth;
        } else {
            // 可以居中显示
            xOffset = centerOffset;
        }
        
        // 如果消息很短，根据发送者调整为靠内侧显示
        if (anchorWidth < popupWidth * 0.6) { // 消息宽度小于弹出菜单宽度的60%时
            if (message.isSentByMe()) {
                // 自己的消息，靠左内侧显示（向消息中心靠拢）
                int innerOffset = anchorWidth - popupWidth;
                // 确保不会溢出屏幕左侧
                if (anchorScreenX + innerOffset >= 0) {
                    xOffset = innerOffset;
                }
            } else {
                // 对方的消息，靠右内侧显示（向消息中心靠拢）
                int innerOffset = 0;
                // 确保不会溢出屏幕右侧
                if (anchorScreenX + popupWidth <= screenWidth) {
                    xOffset = innerOffset;
                }
            }
        }
        
        return xOffset;
    }
    
    /**
     * 计算垂直方向的偏移量
     * 让弹出菜单显示在消息上方且不覆盖消息
     * 
     * @param anchorHeight 锚点视图高度
     * @param popupHeight 弹出菜单高度
     * @return 垂直偏移量
     */
    private int calculateVerticalOffset(int anchorHeight, int popupHeight) {
        // showAsDropDown是相对于anchorView底部的，所以要显示在上方需要：
        // -(anchorView高度 + 弹出菜单高度)
        // 这里减去88是为了微调位置，避免菜单贴得太紧
        return -(anchorHeight + popupHeight - 88);
    }
    
    /**
     * 复制消息到剪贴板
     * 提供默认的复制功能实现
     * 
     * @param message 要复制的消息
     */
    private void copyMessageToClipboard(ChatMessage message) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("聊天消息", message.getContent());
        clipboard.setPrimaryClip(clip);
        
        Toast.makeText(context, "消息已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 获取屏幕宽度
     * @return 屏幕宽度（像素）
     */
    public int getScreenWidth() {
        return context.getResources().getDisplayMetrics().widthPixels;
    }
    
    /**
     * 获取屏幕高度
     * @return 屏幕高度（像素）
     */
    public int getScreenHeight() {
        return context.getResources().getDisplayMetrics().heightPixels;
    }
}