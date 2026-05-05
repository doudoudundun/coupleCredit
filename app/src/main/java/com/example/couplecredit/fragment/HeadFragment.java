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

    private static final int TAB_HOME = 0;
    private static final int TAB_ADD_BILL = 1;
    private static final int TAB_CHAT = 2;
    private static final int TAB_SHARED_PLANS = 3;
    private static final int TAB_REPORT = 4;

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
    private static final String KEY_CURRENT_CHILD_TAG = "currentChildFragmentTag";
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
        // Try to restore existing fragments from FragmentManager
        Fragment existingClassic = fragmentManager.findFragmentByTag("classic");
        Fragment existingChat = fragmentManager.findFragmentByTag("chat");
        Fragment existingSharedPlans = fragmentManager.findFragmentByTag("sharedPlans");
        Fragment existingReport = fragmentManager.findFragmentByTag("report");
        Fragment existingAddBill = fragmentManager.findFragmentByTag("addBill");

        // Use existing fragments if found, otherwise create new ones
        ClassicFragment = existingClassic != null ? existingClassic : new ClassicModelFragment();
        ChatFragment = existingChat != null ? existingChat : new ChatModelFragment();
        SharedPlansFragment = existingSharedPlans != null ? existingSharedPlans : new com.example.couplecredit.fragment.SharedPlansFragment();
        ReportFragment = existingReport != null ? existingReport : new com.example.couplecredit.fragment.ReportFragment();
        AddBillFragment = existingAddBill != null ? existingAddBill : new com.example.couplecredit.fragment.AddBillFragment();

        // Restore current child fragment from saved state
        String currentChildTag = sharedPreferences.getString(KEY_CURRENT_CHILD_TAG, "classic");
        if ("chat".equals(currentChildTag)) {
            currentChildFragment = ChatFragment;
        } else if ("sharedPlans".equals(currentChildTag)) {
            currentChildFragment = SharedPlansFragment;
        } else if ("report".equals(currentChildTag)) {
            currentChildFragment = ReportFragment;
        } else if ("addBill".equals(currentChildTag)) {
            currentChildFragment = AddBillFragment;
        } else {
            currentChildFragment = ClassicFragment;
        }

        // Add fragments to container if not already added
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

        // Hide all except current
        transaction.hide(ClassicFragment);
        transaction.hide(ChatFragment);
        transaction.hide(SharedPlansFragment);
        transaction.hide(ReportFragment);
        transaction.hide(AddBillFragment);

        // Show the current one
        if (currentChildFragment != null) {
            transaction.show(currentChildFragment);
        }

        transaction.commitNow();
    }

    private void showChildFragment(int position) {
        Fragment target;
        switch (position) {
            case TAB_ADD_BILL:
                target = AddBillFragment;
                break;
            case TAB_CHAT:
                target = ChatFragment;
                break;
            case TAB_SHARED_PLANS:
                target = SharedPlansFragment;
                break;
            case TAB_REPORT:
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

        // Save current child fragment tag
        String childTag;
        switch (position) {
            case TAB_ADD_BILL:
                childTag = "addBill";
                break;
            case TAB_CHAT:
                childTag = "chat";
                break;
            case TAB_SHARED_PLANS:
                childTag = "sharedPlans";
                break;
            case TAB_REPORT:
                childTag = "report";
                break;
            default:
                childTag = "classic";
                break;
        }
        editor.putString(KEY_CURRENT_CHILD_TAG, childTag);
        editor.apply();
    }

    public ClassicModelFragment getClassicFragment() {
        return (ClassicModelFragment) ClassicFragment;
    }

    public void refreshCurrentFragmentData() {
        if (currentTabPosition == TAB_HOME && ClassicFragment instanceof ClassicModelFragment) {
            ((ClassicModelFragment) ClassicFragment).refreshBillData();
        } else if (currentTabPosition == TAB_REPORT && ReportFragment instanceof com.example.couplecredit.fragment.ReportFragment) {
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

    public void switchToAddBillTab() {
        if (segmentedTab != null) {
            TabLayout.Tab addBillTab = segmentedTab.getTabAt(TAB_ADD_BILL);
            if (addBillTab != null) {
                addBillTab.select();
            }
        }
    }

    public void switchToReportTab() {
        if (segmentedTab != null) {
            TabLayout.Tab reportTab = segmentedTab.getTabAt(TAB_REPORT);
            if (reportTab != null) {
                reportTab.select();
            }
        }
    }
}
