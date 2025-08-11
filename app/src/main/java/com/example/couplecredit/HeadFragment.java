package com.example.couplecredit;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.NumberPicker;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.transsion.effectengine.bounceeffect.OverScrollDecorHelper;
import com.transsion.widgetslib.widget.OSSegmentedTab;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HeadFragment extends Fragment {
    
    private FragmentManager fragmentManager;
    private Fragment ClassicFragment;
    private Fragment ChatFragment;
    private OSSegmentedTab segmentedTab;
    private List<String> currentTabs = new ArrayList<>();

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
        segmentedTab = view.findViewById(R.id.segmented_tab);
        fragmentManager = getChildFragmentManager();
        ClassicFragment = new ClassicModelFragment();
        ChatFragment = new ChatModelFragment();
        fragmentManager.beginTransaction().add(R.id.fg_change, ClassicFragment).commit();
//
//        tvClassicMode = view.findViewById(R.id.tv_classic_mode);
//        tvChatMode = view.findViewById(R.id.tv_chat_mode);
        
        // 只在第一次创建时添加tabs
        if (currentTabs.isEmpty()) {
            currentTabs.add("经典模式");
            currentTabs.add("聊天模式");
        }
        // 设置分段按钮点击事件
        setupSegmentedTab();
    }
    private void setupSegmentedTab() {
        segmentedTab.addTabs(currentTabs);

        // 设置选中监听
        segmentedTab.setOnTabSelectedListener(new OSSegmentedTab.OnTabSelectedListener() {
            @Override
            public void onTabSelected(int position) {
                if (position == 0) {
                    fragmentManager.beginTransaction().replace(R.id.fg_change, ClassicFragment).commit();
                } else if (position == 1) {
                    fragmentManager.beginTransaction().replace(R.id.fg_change, ChatFragment).commit();
                }
            }
        });
    }



}