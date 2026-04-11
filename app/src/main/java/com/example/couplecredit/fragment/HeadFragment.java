package com.example.couplecredit.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.couplecredit.R;
import com.example.couplecredit.database.DatabaseConnectionPool;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

public class HeadFragment extends Fragment {

    private FragmentManager fragmentManager;
    private Fragment ClassicFragment;
    private Fragment ChatFragment;
    private TabLayout segmentedTab;
    private boolean isClassicMode = true;
    private final List<String> currentTabs = new ArrayList<>();
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "HeadFragmentPrefs";
    private static final String KEY_IS_CLASSIC_MODE = "isClassicMode";
    private final TabLayout.OnTabSelectedListener onTabSelectedListener = new TabLayout.OnTabSelectedListener() {
        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            int position = tab.getPosition();
            if (position == 0) {
                new Thread(() -> DatabaseConnectionPool.getInstance().warmUp()).start();

                if (ClassicFragment == null) {
                    ClassicFragment = new ClassicModelFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ClassicFragment, "classic").commit();
                isClassicMode = true;
                saveMode(true);
            } else if (position == 1) {
                if (ChatFragment == null) {
                    ChatFragment = new ChatModelFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ChatFragment, "chat").commit();
                isClassicMode = false;
                saveMode(false);
            }
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {
        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_head, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isClassicMode = sharedPreferences.getBoolean(KEY_IS_CLASSIC_MODE, true);

        segmentedTab = view.findViewById(R.id.segmented_tab);
        fragmentManager = getChildFragmentManager();

        if (currentTabs.isEmpty()) {
            currentTabs.add("经典模式");
            currentTabs.add("聊天模式");
        }

        Fragment existing = fragmentManager.findFragmentById(R.id.fg_change);
        if (existing == null) {
            ClassicFragment = new ClassicModelFragment();
            ChatFragment = new ChatModelFragment();

            if (isClassicMode) {
                fragmentManager.beginTransaction().replace(R.id.fg_change, ClassicFragment, "classic").commit();
            } else {
                fragmentManager.beginTransaction().replace(R.id.fg_change, ChatFragment, "chat").commit();
            }
        } else if (isClassicMode) {
            ClassicFragment = existing;
        } else {
            ChatFragment = existing;
        }

        setupSegmentedTab();
    }

    private void setupSegmentedTab() {
        if (segmentedTab.getTabCount() == 0) {
            for (String tabTitle : currentTabs) {
                segmentedTab.addTab(segmentedTab.newTab().setText(tabTitle));
            }
        }

        TabLayout.Tab selectedTab = segmentedTab.getTabAt(isClassicMode ? 0 : 1);
        if (selectedTab != null) {
            selectedTab.select();
        }
        segmentedTab.addOnTabSelectedListener(onTabSelectedListener);
    }

    private void saveMode(boolean isClassic) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(KEY_IS_CLASSIC_MODE, isClassic);
        editor.apply();
    }

    public ClassicModelFragment getClassicFragment() {
        return (ClassicModelFragment) ClassicFragment;
    }

    public void refreshCurrentFragmentData() {
        if (isClassicMode && ClassicFragment instanceof ClassicModelFragment) {
            ((ClassicModelFragment) ClassicFragment).refreshBillData();
        }
    }
}
