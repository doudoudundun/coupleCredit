package com.example.couplecredit.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.Html;
import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.couplecredit.ChatBackgroundActivity;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.ToastDemoActivity;
import com.example.couplecredit.activity.UserSettingsActivity;
import com.example.couplecredit.function.UserInfoManager;
import android.content.SharedPreferences;
import android.content.Context;

/**
 * 我的页面Fragment
 * 提供用户个人设置和功能入口
 */
public class MyFragment extends Fragment {
    
    // UI组件声明
    private LinearLayout llChatBackground;  // 聊天背景设置选项容器
    private LinearLayout llToastDemo;       // Toast演示选项容器
    private LinearLayout llLogin;           // 登录选项容器
    private LinearLayout llUserSettings;    // 个人设置选项容器
    private TextView tvLoginText;           // 登录文本
    private View viewSettingsDivider;       // 设置分割线
    
    // 用户信息
    private String username;
    private String userId;
    private boolean isLoggedIn = false;
    
    /**
     * 创建Fragment视图
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 填充布局文件
        return inflater.inflate(R.layout.fragment_my, container, false);
    }
    
    /**
     * 视图创建完成后的初始化工作
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 获取传递的用户信息
        Bundle args = getArguments();
        if (args != null) {
            username = args.getString("username");
            userId = args.getString("id");
            isLoggedIn = (username != null && userId != null);
        }
        
        // 初始化UI组件
        initViews(view);
        // 设置事件监听器
        setupListeners();
        // 更新UI显示
        updateLoginUI();
    }
    
    /**
     * 初始化UI组件
     * @param view 根视图
     */
    private void initViews(View view) {
        llChatBackground = view.findViewById(R.id.ll_chat_background);
        llToastDemo = view.findViewById(R.id.ll_toast_demo);
        llLogin = view.findViewById(R.id.ll_login);
        llUserSettings = view.findViewById(R.id.ll_user_settings);
        tvLoginText = view.findViewById(R.id.tv_login_text);
        viewSettingsDivider = view.findViewById(R.id.view_settings_divider);
    }
    
    /**
     * 设置各种事件监听器
     */
    private void setupListeners() {
        // 设置聊天背景选项点击事件
        llChatBackground.setOnClickListener(v -> {
            // 启动聊天背景设置Activity
            Intent intent = new Intent(getActivity(), ChatBackgroundActivity.class);
            startActivity(intent);
        });
        
        // 设置Toast演示选项点击事件
        llToastDemo.setOnClickListener(v -> {
            // 启动Toast演示Activity
            Intent intent = new Intent(getActivity(), ToastDemoActivity.class);
            startActivity(intent);
        });
        
        // 设置登录选项点击事件
        llLogin.setOnClickListener(v -> {
            if (!isLoggedIn) {
                // 未登录，启动登录Activity
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                startActivity(intent);
            } else {
                // 已登录，跳转到个人设置界面
                Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
                intent.putExtra("username", username);
                intent.putExtra("id", userId);
                startActivity(intent);
            }
        });
        
        // 设置个人设置选项点击事件
        llUserSettings.setOnClickListener(v -> {
            // 跳转到个人设置界面
            Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // 每次Fragment可见时检查用户登录状态
        checkUserLoginStatus();
    }
    
    /**
     * 检查用户登录状态
     */
    private void checkUserLoginStatus() {
        if (getActivity() != null) {
            // 使用UserInfoManager检查登录状态
            boolean isLoggedIn = UserInfoManager.isUserLoggedIn(getActivity());
            
            if (isLoggedIn) {
                // 从UserInfoManager读取用户信息
                username = UserInfoManager.getCurrentUsername(getActivity());
                int userIdInt = UserInfoManager.getCurrentUserId(getActivity());
                if (userIdInt != -1) {
                    userId = String.valueOf(userIdInt);
                    this.isLoggedIn = true;
                } else {
                    this.isLoggedIn = false;
                }
            } else {
                // 清空用户信息
                username = null;
                userId = null;
                this.isLoggedIn = false;
            }
            
            // 更新UI显示
            updateLoginUI();
        }
    }
    
    /**
     * 更新登录UI显示
     */
    private void updateLoginUI() {
        if (isLoggedIn && tvLoginText != null) {
            // 已登录，显示用户信息
            String htmlText = "<big><b>" + username + "</b></big><br><small>ID: " + userId + "</small>";
            Spanned spannedText = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY);
            tvLoginText.setText(spannedText);
            
            // 显示个人设置选项
            llUserSettings.setVisibility(View.VISIBLE);
            viewSettingsDivider.setVisibility(View.VISIBLE);
        } else if (tvLoginText != null) {
            // 未登录，显示登录提示
            tvLoginText.setText("点我立即登录");
            
            // 隐藏个人设置选项
            llUserSettings.setVisibility(View.GONE);
            viewSettingsDivider.setVisibility(View.GONE);
        }
    }

}