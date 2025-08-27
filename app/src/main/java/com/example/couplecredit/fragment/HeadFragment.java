package com.example.couplecredit.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.couplecredit.R;
import com.transsion.widgetslib.widget.OSSegmentedTab;

import java.util.ArrayList;
import java.util.List;

public class HeadFragment extends Fragment {
    
    private FragmentManager fragmentManager;
    private Fragment ClassicFragment;
    private Fragment ChatFragment;
    private OSSegmentedTab segmentedTab;
    private boolean isClassicMode = true;
    private List<String> currentTabs = new ArrayList<>();
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "HeadFragmentPrefs";
    private static final String KEY_IS_CLASSIC_MODE = "isClassicMode";
    private OSSegmentedTab.OnTabSelectedListener onTabSelectedListener = new OSSegmentedTab.OnTabSelectedListener() {
        @Override
        public void onTabSelected(int position) {
            if (position == 0) {
                if (ClassicFragment == null) {
                    ClassicFragment = new ClassicModelFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ClassicFragment, "classic").commit();
                isClassicMode = true;
                // 添加保存状态到SharedPreferences
                saveMode(true);
            } else if (position == 1) {
                if (ChatFragment == null) {
                    ChatFragment = new ChatModelFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ChatFragment, "chat").commit();
                isClassicMode = false;
                // 添加保存状态到SharedPreferences
                saveMode(false);
            }
        }
    };

//    // 模式常量
//    private static final int MODE_CLASSIC = 0;
//    private static final int MODE_CHAT = 1;
//    private int currentMode = MODE_CLASSIC;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_head, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化SharedPreferences
        sharedPreferences = getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // 从SharedPreferences读取保存的模式状态
        isClassicMode = sharedPreferences.getBoolean(KEY_IS_CLASSIC_MODE, true);
        // 读取模式状态
        
        segmentedTab = view.findViewById(R.id.segmented_tab);
        fragmentManager = getChildFragmentManager();
        
        // 只在第一次创建时添加tabs
        if (currentTabs.isEmpty()) {
            currentTabs.add("经典模式");
            currentTabs.add("聊天模式");
        }
        
        // 设置分段按钮点击事件
        setupSegmentedTab();
        
        // 检查是否已有子Fragment存在
        Fragment existing = fragmentManager.findFragmentById(R.id.fg_change);
        if (existing == null) {
            // 首次创建：根据保存的状态显示对应的Fragment
            ClassicFragment = new ClassicModelFragment();
            ChatFragment = new ChatModelFragment();
            
            if (isClassicMode) {
                fragmentManager.beginTransaction().replace(R.id.fg_change, ClassicFragment, "classic").commit();
            } else {
                fragmentManager.beginTransaction().replace(R.id.fg_change, ChatFragment, "chat").commit();
            }
        } else {
            // 已存在子Fragment，获取引用
            if (isClassicMode) {
                ClassicFragment = existing;
            } else {
                ChatFragment = existing;
            }
        }
    }
    private void setupSegmentedTab() {
        segmentedTab.addTabs(currentTabs);

        // 设置选中监听
        segmentedTab.setOnTabSelectedListener(onTabSelectedListener);
    }
    
    private void saveMode(boolean isClassic) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(KEY_IS_CLASSIC_MODE, isClassic);
        editor.apply();
        // 保存模式状态
    }
    
    // 提供获取ClassicModelFragment的方法
    public ClassicModelFragment getClassicFragment() {
        return (ClassicModelFragment) ClassicFragment;
    }



}