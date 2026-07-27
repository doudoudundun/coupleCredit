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
import java.util.function.Supplier;

public class HeadFragment extends Fragment {

    private static final int TAB_HOME = 0;
    private static final int TAB_ADD_BILL = 1;
    private static final int TAB_CHAT = 2;
    private static final int TAB_SHARED_PLANS = 3;
    private static final int TAB_REPORT = 4;

    // 子 Fragment tags
    private static final String TAG_CLASSIC = "classic";
    private static final String TAG_ADD_BILL = "addBill";
    private static final String TAG_CHAT = "chat";
    private static final String TAG_SHARED_PLANS = "sharedPlans";
    private static final String TAG_REPORT = "report";

    private FragmentManager fragmentManager;
    // Classic（首页）保持饿汉创建，作为默认落地 tab
    private Fragment ClassicFragment;
    // 其余子 Fragment 改为懒创建
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

    /**
     * 懒加载策略：
     * - Classic（默认落地 tab）总是饿汉创建并显示
     * - 持久化的当前 tab 也饿汉创建（确保恢复用户上次位置）
     * - 其余子 Fragment 留待 showChildFragment 时懒创建
     */
    private void restoreOrCreateChildFragments() {
        // 先尝试从 FragmentManager 恢复
        ClassicFragment = fragmentManager.findFragmentByTag(TAG_CLASSIC);
        ChatFragment = fragmentManager.findFragmentByTag(TAG_CHAT);
        SharedPlansFragment = fragmentManager.findFragmentByTag(TAG_SHARED_PLANS);
        ReportFragment = fragmentManager.findFragmentByTag(TAG_REPORT);
        AddBillFragment = fragmentManager.findFragmentByTag(TAG_ADD_BILL);

        if (ClassicFragment == null) ClassicFragment = new ClassicModelFragment();

        // 解析持久化的当前 child tag
        String currentChildTag = sharedPreferences.getString(KEY_CURRENT_CHILD_TAG, TAG_CLASSIC);
        // Classic 始终创建；其他 tab 仅在持久化等于该 tab 时才饿汉创建
        boolean needReportEager = TAG_REPORT.equals(currentChildTag);
        boolean needChatEager = TAG_CHAT.equals(currentChildTag);
        boolean needSharedPlansEager = TAG_SHARED_PLANS.equals(currentChildTag);
        boolean needAddBillEager = TAG_ADD_BILL.equals(currentChildTag);

        if (needChatEager && ChatFragment == null) ChatFragment = new ChatModelFragment();
        if (needSharedPlansEager && SharedPlansFragment == null) SharedPlansFragment = new com.example.couplecredit.fragment.SharedPlansFragment();
        if (needReportEager && ReportFragment == null) ReportFragment = new com.example.couplecredit.fragment.ReportFragment();
        if (needAddBillEager && AddBillFragment == null) AddBillFragment = new com.example.couplecredit.fragment.AddBillFragment();

        // 解析 currentChildFragment
        switch (currentChildTag) {
            case TAG_CHAT:
                currentChildFragment = ChatFragment;
                break;
            case TAG_SHARED_PLANS:
                currentChildFragment = SharedPlansFragment;
                break;
            case TAG_REPORT:
                currentChildFragment = ReportFragment;
                break;
            case TAG_ADD_BILL:
                currentChildFragment = AddBillFragment;
                break;
            default:
                currentChildFragment = ClassicFragment;
                break;
        }
        // 若 tag 指向的子 Fragment 因某种原因未创建，回退到 Classic
        if (currentChildFragment == null) {
            currentChildFragment = ClassicFragment;
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);

        // 非当前的子 Fragment：add + hide（不可见）
        // 当前的子 Fragment（currentChildFragment）：只 add（默认可见），不能 hide 后再 show
        // —— 因为 FragmentTransaction 是批处理延迟执行的，commit() 前 isAdded() 始终为 false，
        //    若先 hide 再判断 isAdded() 决定是否 show，show 会被错误跳过，导致当前 fragment 永远 hidden（白屏）。

        if (ClassicFragment != null && ClassicFragment != currentChildFragment) {
            if (!ClassicFragment.isAdded()) transaction.add(R.id.fg_change, ClassicFragment, TAG_CLASSIC);
            transaction.hide(ClassicFragment);
        }
        if (ChatFragment != null && ChatFragment != currentChildFragment) {
            if (!ChatFragment.isAdded()) transaction.add(R.id.fg_change, ChatFragment, TAG_CHAT);
            transaction.hide(ChatFragment);
        }
        if (SharedPlansFragment != null && SharedPlansFragment != currentChildFragment) {
            if (!SharedPlansFragment.isAdded()) transaction.add(R.id.fg_change, SharedPlansFragment, TAG_SHARED_PLANS);
            transaction.hide(SharedPlansFragment);
        }
        if (ReportFragment != null && ReportFragment != currentChildFragment) {
            if (!ReportFragment.isAdded()) transaction.add(R.id.fg_change, ReportFragment, TAG_REPORT);
            transaction.hide(ReportFragment);
        }
        if (AddBillFragment != null && AddBillFragment != currentChildFragment) {
            if (!AddBillFragment.isAdded()) transaction.add(R.id.fg_change, AddBillFragment, TAG_ADD_BILL);
            transaction.hide(AddBillFragment);
        }

        // 当前的子 Fragment：add（默认 visible）；若之前是 hidden 则 show
        if (currentChildFragment != null) {
            if (!currentChildFragment.isAdded()) {
                String tag = getChildTag(currentChildFragment);
                transaction.add(R.id.fg_change, currentChildFragment, tag);
            }
            transaction.show(currentChildFragment);
        }

        transaction.commit();
    }

    private String getChildTag(Fragment fragment) {
        if (fragment instanceof ClassicModelFragment) return TAG_CLASSIC;
        if (fragment instanceof ChatModelFragment) return TAG_CHAT;
        if (fragment instanceof com.example.couplecredit.fragment.SharedPlansFragment) return TAG_SHARED_PLANS;
        if (fragment instanceof com.example.couplecredit.fragment.ReportFragment) return TAG_REPORT;
        if (fragment instanceof com.example.couplecredit.fragment.AddBillFragment) return TAG_ADD_BILL;
        return TAG_CLASSIC;
    }

    /**
     * 懒创建/获取子 Fragment（按 tag）。仅创建实例并缓存，add 操作由 showChildFragment 负责。
     */
    private Fragment getOrCreateChild(String tag, Supplier<Fragment> factory) {
        Fragment existing = fragmentManager.findFragmentByTag(tag);
        if (existing != null) return existing;
        // 找缓存的字段
        switch (tag) {
            case TAG_CLASSIC: return ClassicFragment != null ? ClassicFragment : (ClassicFragment = new ClassicModelFragment());
            case TAG_CHAT: return ChatFragment != null ? ChatFragment : (ChatFragment = new ChatModelFragment());
            case TAG_SHARED_PLANS: return SharedPlansFragment != null ? SharedPlansFragment : (SharedPlansFragment = new com.example.couplecredit.fragment.SharedPlansFragment());
            case TAG_REPORT: return ReportFragment != null ? ReportFragment : (ReportFragment = new com.example.couplecredit.fragment.ReportFragment());
            case TAG_ADD_BILL: return AddBillFragment != null ? AddBillFragment : (AddBillFragment = new com.example.couplecredit.fragment.AddBillFragment());
        }
        return factory.get();
    }

    private void showChildFragment(int position) {
        Fragment target;
        String tag;
        switch (position) {
            case TAB_ADD_BILL:
                tag = TAG_ADD_BILL;
                target = getOrCreateChild(tag, com.example.couplecredit.fragment.AddBillFragment::new);
                AddBillFragment = target;
                break;
            case TAB_CHAT:
                tag = TAG_CHAT;
                target = getOrCreateChild(tag, ChatModelFragment::new);
                ChatFragment = target;
                break;
            case TAB_SHARED_PLANS:
                tag = TAG_SHARED_PLANS;
                target = getOrCreateChild(tag, com.example.couplecredit.fragment.SharedPlansFragment::new);
                SharedPlansFragment = target;
                break;
            case TAB_REPORT:
                tag = TAG_REPORT;
                target = getOrCreateChild(tag, com.example.couplecredit.fragment.ReportFragment::new);
                ReportFragment = target;
                break;
            default:
                tag = TAG_CLASSIC;
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
        if (!target.isAdded()) {
            transaction.add(R.id.fg_change, target, tag);
        }
        transaction.show(target);
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
                childTag = TAG_ADD_BILL;
                break;
            case TAB_CHAT:
                childTag = TAG_CHAT;
                break;
            case TAB_SHARED_PLANS:
                childTag = TAG_SHARED_PLANS;
                break;
            case TAB_REPORT:
                childTag = TAG_REPORT;
                break;
            default:
                childTag = TAG_CLASSIC;
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

    /**
     * 获取 ReportFragment；若从未创建则同步懒创建。
     * ClassicModelFragment 在切到 report tab 后会立即调用此方法。
     */
    public com.example.couplecredit.fragment.ReportFragment getReportFragment() {
        if (ReportFragment == null) {
            // 同步懒创建并 add 到容器（隐藏），保证调用方拿到的实例已被 attach
            ReportFragment = getOrCreateChild(TAG_REPORT, com.example.couplecredit.fragment.ReportFragment::new);
            ensureChildAddedHidden(ReportFragment, TAG_REPORT);
        }
        if (ReportFragment instanceof com.example.couplecredit.fragment.ReportFragment) {
            return (com.example.couplecredit.fragment.ReportFragment) ReportFragment;
        }
        return null;
    }

    public com.example.couplecredit.fragment.SharedPlansFragment getSharedPlansFragment() {
        if (SharedPlansFragment == null) {
            SharedPlansFragment = getOrCreateChild(TAG_SHARED_PLANS, com.example.couplecredit.fragment.SharedPlansFragment::new);
            ensureChildAddedHidden(SharedPlansFragment, TAG_SHARED_PLANS);
        }
        if (SharedPlansFragment instanceof com.example.couplecredit.fragment.SharedPlansFragment) {
            return (com.example.couplecredit.fragment.SharedPlansFragment) SharedPlansFragment;
        }
        return null;
    }

    /**
     * 若 Fragment 未 attach，用 commitNow 同步 add 并 hide（确保 getReportFragment() 之后的同步调用安全）。
     */
    private void ensureChildAddedHidden(Fragment fragment, String tag) {
        if (fragment == null || fragment.isAdded()) return;
        FragmentTransaction t = fragmentManager.beginTransaction().setReorderingAllowed(true);
        t.add(R.id.fg_change, fragment, tag);
        t.hide(fragment);
        if (currentChildFragment != null && currentChildFragment.isAdded()) {
            t.show(currentChildFragment);
        }
        t.commitNow();
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
            // 预先同步懒创建 ReportFragment，避免 listener 异步创建导致 getReportFragment() 返回 null
            if (ReportFragment == null) {
                ReportFragment = getOrCreateChild(TAG_REPORT, com.example.couplecredit.fragment.ReportFragment::new);
                ensureChildAddedHidden(ReportFragment, TAG_REPORT);
            }
            TabLayout.Tab reportTab = segmentedTab.getTabAt(TAB_REPORT);
            if (reportTab != null) {
                reportTab.select();
            }
        }
    }
}
