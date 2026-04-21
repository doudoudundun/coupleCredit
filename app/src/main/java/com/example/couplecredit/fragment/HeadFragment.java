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
import androidx.fragment.app.FragmentTransaction;

import com.example.couplecredit.R;
import com.example.couplecredit.activity.MainActivity;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

public class HeadFragment extends Fragment {

    private FragmentManager fragmentManager;
    private Fragment ClassicFragment;
    private Fragment ChatFragment;
    private Fragment ReportFragment;
    private Fragment SharedPlansFragment;
    private Fragment AddBillFragment;
    private Fragment currentChildFragment;
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
            showChildFragment(position);
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
            currentTabs.add("记账");
            currentTabs.add("聊天");
            currentTabs.add("计划");
            currentTabs.add("报表");
        }

        restoreOrCreateChildFragments();
        setupSegmentedTab();
        showChildFragment(currentTabPosition);
    }

    private void restoreOrCreateChildFragments() {
        ClassicFragment = fragmentManager.findFragmentByTag("classic");
        ChatFragment = fragmentManager.findFragmentByTag("chat");
        SharedPlansFragment = fragmentManager.findFragmentByTag("sharedPlans");
        ReportFragment = fragmentManager.findFragmentByTag("report");
        AddBillFragment = fragmentManager.findFragmentByTag("addBill");

        if (ClassicFragment == null) {
            ClassicFragment = new ClassicModelFragment();
        }
        if (ChatFragment == null) {
            ChatFragment = new ChatModelFragment();
        }
        if (SharedPlansFragment == null) {
            SharedPlansFragment = new com.example.couplecredit.fragment.SharedPlansFragment();
        }
        if (ReportFragment == null) {
            ReportFragment = new com.example.couplecredit.fragment.ReportFragment();
        }
        if (AddBillFragment == null) {
            AddBillFragment = new com.example.couplecredit.fragment.AddBillFragment();
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        if (!ClassicFragment.isAdded()) {
            transaction.add(R.id.fg_change, ClassicFragment, "classic");
        }
        if (!ChatFragment.isAdded()) {
            transaction.add(R.id.fg_change, ChatFragment, "chat");
        }
        if (!SharedPlansFragment.isAdded()) {
            transaction.add(R.id.fg_change, SharedPlansFragment, "sharedPlans");
        }
        if (!ReportFragment.isAdded()) {
            transaction.add(R.id.fg_change, ReportFragment, "report");
        }
        if (!AddBillFragment.isAdded()) {
            transaction.add(R.id.fg_change, AddBillFragment, "addBill");
        }
        transaction.hide(ClassicFragment);
        transaction.hide(ChatFragment);
        transaction.hide(SharedPlansFragment);
        transaction.hide(ReportFragment);
        transaction.hide(AddBillFragment);
        transaction.commitNow();
        currentChildFragment = null;
    }

    private void showChildFragment(int position) {
        Fragment target;
        switch (position) {
            case 1:
                target = AddBillFragment;
                break;
            case 2:
                target = ChatFragment;
                break;
            case 3:
                target = SharedPlansFragment;
                break;
            case 4:
                target = ReportFragment;
                break;
            default:
                target = ClassicFragment;
                break;
        }

        if (target == null || target == currentChildFragment) {
            return;
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        if (currentChildFragment != null && currentChildFragment.isAdded()) {
            transaction.hide(currentChildFragment);
        }
        if (target.isAdded()) {
            transaction.show(target);
        }
        transaction.commit();
        currentChildFragment = target;
    }

    private void setupSegmentedTab() {
        if (segmentedTab.getTabCount() == 0) {
            for (String tabTitle : currentTabs) {
                segmentedTab.addTab(segmentedTab.newTab().setText(tabTitle));
            }
        }

        TabLayout.Tab selectedTab = segmentedTab.getTabAt(currentTabPosition);
        if (selectedTab != null && !selectedTab.isSelected()) {
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
        } else if (currentTabPosition == 4 && ReportFragment instanceof com.example.couplecredit.fragment.ReportFragment) {
            ((com.example.couplecredit.fragment.ReportFragment) ReportFragment).refreshChartData();
        }
    }

    public com.example.couplecredit.fragment.ReportFragment getReportFragment() {
        if (ReportFragment instanceof com.example.couplecredit.fragment.ReportFragment) {
            return (com.example.couplecredit.fragment.ReportFragment) ReportFragment;
        }
        return null;
    }

    public com.example.couplecredit.fragment.SharedPlansFragment getSharedPlansFragment() {
        if (SharedPlansFragment instanceof com.example.couplecredit.fragment.SharedPlansFragment) {
            return (com.example.couplecredit.fragment.SharedPlansFragment) SharedPlansFragment;
        }
        return null;
    }

    public void switchToReportTab() {
        if (segmentedTab != null) {
            TabLayout.Tab reportTab = segmentedTab.getTabAt(4);
            if (reportTab != null) {
                reportTab.select();
            }
        }
    }
}
