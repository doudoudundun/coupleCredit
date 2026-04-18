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
    private Fragment ReportFragment;
    private TabLayout segmentedTab;
    private int currentTabPosition = 0;
    private final List<String> currentTabs = new ArrayList<>();
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "HeadFragmentPrefs";
    private static final String KEY_TAB_POSITION = "tabPosition";
    private final TabLayout.OnTabSelectedListener onTabSelectedListener = new TabLayout.OnTabSelectedListener() {
        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            int position = tab.getPosition();
            currentTabPosition = position;
            saveTabPosition(position);

            if (position == 0) {
                new Thread(() -> DatabaseConnectionPool.getInstance().warmUp()).start();
                if (ClassicFragment == null) {
                    ClassicFragment = new ClassicModelFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ClassicFragment, "classic").commit();
            } else if (position == 1) {
                if (ChatFragment == null) {
                    ChatFragment = new ChatModelFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ChatFragment, "chat").commit();
            } else if (position == 2) {
                if (ReportFragment == null) {
                    ReportFragment = new com.example.couplecredit.fragment.ReportFragment();
                }
                fragmentManager.beginTransaction().replace(R.id.fg_change, ReportFragment, "report").commit();
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
        currentTabPosition = sharedPreferences.getInt(KEY_TAB_POSITION, 0);

        segmentedTab = view.findViewById(R.id.segmented_tab);
        fragmentManager = getChildFragmentManager();

        if (currentTabs.isEmpty()) {
            currentTabs.add("首页");
            currentTabs.add("聊天");
            currentTabs.add("报表");
        }

        Fragment existing = fragmentManager.findFragmentById(R.id.fg_change);
        if (existing == null) {
            ClassicFragment = new ClassicModelFragment();
            ChatFragment = new ChatModelFragment();
            ReportFragment = new com.example.couplecredit.fragment.ReportFragment();

            Fragment initial;
            String tag;
            switch (currentTabPosition) {
                case 1: initial = ChatFragment; tag = "chat"; break;
                case 2: initial = ReportFragment; tag = "report"; break;
                default: initial = ClassicFragment; tag = "classic"; break;
            }
            fragmentManager.beginTransaction().replace(R.id.fg_change, initial, tag).commit();
        } else {
            switch (currentTabPosition) {
                case 0: ClassicFragment = existing; break;
                case 1: ChatFragment = existing; break;
                default: ReportFragment = existing; break;
            }
        }

        setupSegmentedTab();
    }

    private void setupSegmentedTab() {
        if (segmentedTab.getTabCount() == 0) {
            for (String tabTitle : currentTabs) {
                segmentedTab.addTab(segmentedTab.newTab().setText(tabTitle));
            }
        }

        TabLayout.Tab selectedTab = segmentedTab.getTabAt(currentTabPosition);
        if (selectedTab != null) {
            selectedTab.select();
        }
        segmentedTab.addOnTabSelectedListener(onTabSelectedListener);
    }

    private void saveTabPosition(int position) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(KEY_TAB_POSITION, position);
        editor.apply();
    }

    public ClassicModelFragment getClassicFragment() {
        return (ClassicModelFragment) ClassicFragment;
    }

    public void refreshCurrentFragmentData() {
        if (currentTabPosition == 0 && ClassicFragment instanceof ClassicModelFragment) {
            ((ClassicModelFragment) ClassicFragment).refreshBillData();
        } else if (currentTabPosition == 2 && ReportFragment instanceof com.example.couplecredit.fragment.ReportFragment) {
            ((com.example.couplecredit.fragment.ReportFragment) ReportFragment).refreshChartData();
        }
    }

    public com.example.couplecredit.fragment.ReportFragment getReportFragment() {
        if (ReportFragment instanceof com.example.couplecredit.fragment.ReportFragment) {
            return (com.example.couplecredit.fragment.ReportFragment) ReportFragment;
        }
        return null;
    }

    public void switchToReportTab() {
        if (segmentedTab != null) {
            TabLayout.Tab reportTab = segmentedTab.getTabAt(2);
            if (reportTab != null) {
                reportTab.select();
            }
        }
    }
}
