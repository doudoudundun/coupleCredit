package com.example.couplecredit;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 我的页面Fragment
 * 提供用户个人设置和功能入口
 */
public class MyFragment extends Fragment {
    
    // UI组件声明
    private LinearLayout llChatBackground;  // 聊天背景设置选项容器
    
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
        
        // 初始化UI组件
        initViews(view);
        // 设置事件监听器
        setupListeners();
    }
    
    /**
     * 初始化UI组件
     * @param view 根视图
     */
    private void initViews(View view) {
        llChatBackground = view.findViewById(R.id.ll_chat_background);
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
    }
}