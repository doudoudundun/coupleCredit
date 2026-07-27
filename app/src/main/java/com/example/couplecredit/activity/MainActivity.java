package com.example.couplecredit.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.DataRefreshBus;
import com.example.couplecredit.utils.NotificationHelper;
import com.example.couplecredit.utils.PollingManager;
import com.example.couplecredit.fragment.HeadFragment;
import com.example.couplecredit.fragment.InventoryFragment;
import com.example.couplecredit.fragment.MyFragment;
import com.example.couplecredit.fragment.RecipeFragment;
import com.example.couplecredit.fragment.EatOutFragment;
import com.example.couplecredit.fragment.TodoFragment;
import com.example.couplecredit.repository.ChatRepository;
import com.example.couplecredit.utils.UserInfoManager;
import com.github.mikephil.charting.utils.Utils;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MainActivity extends AppCompatActivity {

    // Fragment tags —— 一切导航以 tag 为准，不再依赖实例相等
    private static final String TAG_HEAD = "head";
    private static final String TAG_INVENTORY = "inventory";
    private static final String TAG_RECIPE = "recipe";
    private static final String TAG_EATOUT = "eatOut";
    private static final String TAG_TODO = "todo";
    private static final String TAG_MY = "my";

    private FragmentManager fragmentManager;
    // headFragment 保持饿汉式（默认落地页）
    private HeadFragment headFragment;
    // 其余 5 个改为懒创建：缓存已创建的实例
    private final Map<String, Fragment> fragmentPool = new HashMap<>();

    private BottomNavigationView mBottomNav;
    private Fragment currentFragment;
    private Fragment lastTabFragment;
    private volatile ChatRepository chatRepository;

    private static final String KEY_CURRENT_FRAGMENT_TAG = "currentFragmentTag";
    private static final String KEY_LAST_TAB_FRAGMENT_TAG = "lastTabFragmentTag";

    private BroadcastReceiver loginStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("MainActivity", "收到登录状态变化广播: " + intent.getAction());
            refreshAllFragmentsLoginState();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        Utils.init(this);

        // ChatRepository 构造无 I/O，主线程同步创建，避免后续 getChatRepository() 竞态
        try {
            chatRepository = new ChatRepository(this);
            Log.d("MainActivity", "聊天数据仓库初始化完成");
            new Thread(() -> {
                try {
                    if (UserInfoManager.isUserLoggedIn(this)) {
                        Log.d("MainActivity", "用户已登录，开始预加载聊天数据");
                        chatRepository.loadAllMessages();
                        Log.d("MainActivity", "聊天数据预加载完成");
                    } else {
                        Log.d("MainActivity", "用户未登录，跳过聊天数据预加载");
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "聊天数据预加载失败", e);
                }
            }, "ChatDataPreloader").start();
        } catch (Exception e) {
            Log.e("MainActivity", "聊天数据仓库初始化失败", e);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        PollingManager.getInstance().setAppContext(this);

        fragmentManager = getSupportFragmentManager();

        Intent intent = getIntent();
        String username = intent.getStringExtra("username");
        String id = intent.getStringExtra("id");

        if (username == null || id == null) {
            if (UserInfoManager.isUserLoggedIn(this)) {
                username = UserInfoManager.getCurrentUsername(this);
                int userId = UserInfoManager.getCurrentUserId(this);
                if (userId != -1) {
                    id = String.valueOf(userId);
                }
            }
        }

        // headFragment 始终饿汉创建
        headFragment = (HeadFragment) fragmentManager.findFragmentByTag(TAG_HEAD);
        if (headFragment == null) {
            headFragment = new HeadFragment();
        }

        // savedInstanceState != null 时，从 FragmentManager 恢复所有已存在的懒 Fragment（不需要主动创建）
        // 未在 manager 中的 Fragment 保持 null，第一次访问时由 getOrCreate 懒创建
        if (savedInstanceState == null) {
            FragmentTransaction addHead = fragmentManager.beginTransaction().setReorderingAllowed(true);
            if (!headFragment.isAdded()) {
                addHead.add(R.id.fragment_container, headFragment, TAG_HEAD);
            }
            // 用 commitNow 同步执行 add 事务，保证 headFragment 已 attached，
            // 随后的 navigateToHome→showFragment 能正确走 show 流程。
            // 注意：不在此处赋值 currentFragment，让 showFragment 完整执行 show/hide 逻辑，
            // 否则 showFragment 开头的 currentFragment==fragment 判断会直接 return，导致首屏白屏。
            addHead.commitNow();
        } else {
            String currentTag = savedInstanceState.getString(KEY_CURRENT_FRAGMENT_TAG, TAG_HEAD);
            Fragment restored = fragmentManager.findFragmentByTag(currentTag);
            currentFragment = restored != null ? restored : headFragment;

            String lastTabTag = savedInstanceState.getString(KEY_LAST_TAB_FRAGMENT_TAG, null);
            if (lastTabTag != null) {
                lastTabFragment = fragmentManager.findFragmentByTag(lastTabTag);
            }
        }

        mBottomNav = findViewById(R.id.bottom_nav);
        // 用 final 副本捕获，供 lambda 透传给 MyFragment
        final String finalUsername = username;
        final String finalId = id;
        mBottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_head) {
                showFragment(headFragment);
                return true;
            } else if (itemId == R.id.nav_inventory) {
                showFragment(getOrCreateFragment(TAG_INVENTORY, InventoryFragment::new));
                return true;
            } else if (itemId == R.id.nav_recipe) {
                showFragment(getOrCreateFragment(TAG_RECIPE, RecipeFragment::new));
                return true;
            } else if (itemId == R.id.nav_todo) {
                showFragment(getOrCreateFragment(TAG_TODO, TodoFragment::new));
                return true;
            } else if (itemId == R.id.nav_my) {
                Fragment my = getOrCreateFragment(TAG_MY, MyFragment::new);
                // 登录用户信息透传给 MyFragment（首次创建时）
                if (finalUsername != null && finalId != null && !my.isAdded() && my.getArguments() == null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("username", finalUsername);
                    bundle.putString("id", finalId);
                    my.setArguments(bundle);
                }
                showFragment(my);
                return true;
            }
            return false;
        });
        if (savedInstanceState == null) {
            navigateToHome();
        } else {
            // 确保恢复的 Fragment 被正确显示
            if (currentFragment == null) {
                currentFragment = headFragment;
            }
            Fragment target = currentFragment;
            currentFragment = null; // 重置，强制 showFragment 执行完整的 show/hide 逻辑
            showFragment(target);
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
                loginStateReceiver,
                new IntentFilter("com.example.couplecredit.USER_LOGIN")
        );
        LocalBroadcastManager.getInstance(this).registerReceiver(
                loginStateReceiver,
                new IntentFilter("com.example.couplecredit.USER_LOGOUT")
        );

        ViewCompat.setOnApplyWindowInsetsListener(mBottomNav, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom);
            return insets;
        });

        // Listen for back stack changes to sync fragment visibility
        fragmentManager.addOnBackStackChangedListener(() -> {
            Fragment top = fragmentManager.findFragmentById(R.id.fragment_container);
            if (top != null && top != currentFragment && isOneOfTopFragments(top)) {
                currentFragment = top;
                syncBottomNavigationSelection(top);
            }
        });
    }

    /**
     * 懒创建/获取顶层 Fragment。
     * - 先从 FragmentManager 查找（恢复场景）
     * - 再从本地缓存查找（同一会话内已创建）
     * - 否则用 factory 创建并缓存（但不 add 到 manager，由 showFragment 负责 add）
     */
    private Fragment getOrCreateFragment(String tag, Supplier<Fragment> factory) {
        Fragment existing = fragmentManager.findFragmentByTag(tag);
        if (existing != null) {
            fragmentPool.put(tag, existing);
            return existing;
        }
        Fragment cached = fragmentPool.get(tag);
        if (cached != null) {
            return cached;
        }
        Fragment created = factory.get();
        fragmentPool.put(tag, created);
        return created;
    }

    private boolean isOneOfTopFragments(Fragment fragment) {
        return fragment == headFragment
                || fragmentPool.containsValue(fragment);
    }

    public void showFragment(Fragment fragment) {
        if (fragment == null) {
            return;
        }
        if (fragment == currentFragment) {
            syncBottomNavigationSelection(fragment);
            return;
        }

        // Pop all back stack entries (sub-pages like BeadBlueprintListFragment, etc.)
        // before switching tabs, otherwise the sub-page overlay causes white screen.
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);

        // 懒加载：尚未 add 的 Fragment 先 add
        if (!fragment.isAdded()) {
            String tag = getFragmentTag(fragment);
            transaction.add(R.id.fragment_container, fragment, tag != null ? tag : TAG_HEAD);
        }

        for (Fragment existingFragment : fragmentManager.getFragments()) {
            if (existingFragment != null && existingFragment.isAdded()) {
                if (existingFragment == fragment) {
                    transaction.show(existingFragment);
                } else {
                    transaction.hide(existingFragment);
                }
            }
        }

        currentFragment = fragment;
        transaction.commit();
        syncBottomNavigationSelection(fragment);

        if (fragment instanceof InventoryFragment) {
            ((InventoryFragment) fragment).refreshInventoryData();
            PollingManager.getInstance().setRefreshAction(((InventoryFragment) fragment)::refreshInventoryData);
        } else if (fragment instanceof RecipeFragment) {
            ((RecipeFragment) fragment).refreshData();
            PollingManager.getInstance().setRefreshAction(((RecipeFragment) fragment)::refreshData);
        } else if (fragment instanceof EatOutFragment) {
            ((EatOutFragment) fragment).refreshData();
            PollingManager.getInstance().setRefreshAction(((EatOutFragment) fragment)::refreshData);
        } else if (fragment instanceof TodoFragment) {
            ((TodoFragment) fragment).refreshData();
            PollingManager.getInstance().setRefreshAction(((TodoFragment) fragment)::refreshData);
        } else {
            PollingManager.getInstance().setRefreshAction(null);
        }
    }

    public void navigateToHome() {
        if (mBottomNav != null && mBottomNav.getSelectedItemId() != R.id.nav_head) {
            mBottomNav.setSelectedItemId(R.id.nav_head);
        } else {
            showFragment(headFragment);
        }
    }

    public void navigateToInventory() {
        if (mBottomNav != null && mBottomNav.getSelectedItemId() != R.id.nav_inventory) {
            mBottomNav.setSelectedItemId(R.id.nav_inventory);
        } else {
            showFragment(getOrCreateFragment(TAG_INVENTORY, InventoryFragment::new));
        }
    }

    public void navigateToRecipe() {
        if (mBottomNav != null && mBottomNav.getSelectedItemId() != R.id.nav_recipe) {
            mBottomNav.setSelectedItemId(R.id.nav_recipe);
        } else {
            showFragment(getOrCreateFragment(TAG_RECIPE, RecipeFragment::new));
        }
    }

    public void navigateToTodo() {
        if (mBottomNav != null && mBottomNav.getSelectedItemId() != R.id.nav_todo) {
            mBottomNav.setSelectedItemId(R.id.nav_todo);
        } else {
            showFragment(getOrCreateFragment(TAG_TODO, TodoFragment::new));
        }
    }

    public void navigateToAddBillTab() {
        navigateToHome();
        if (headFragment != null) {
            headFragment.switchToAddBillTab();
        }
    }

    public void navigateToReportTab() {
        navigateToHome();
        if (headFragment != null) {
            headFragment.switchToReportTab();
        }
    }

    public void showEatOutFragment() {
        EatOutFragment eatOut = (EatOutFragment) getOrCreateFragment(TAG_EATOUT, EatOutFragment::new);
        if (eatOut == currentFragment) return;

        // Pop back stack to clear any sub-page overlays
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        lastTabFragment = currentFragment;

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        if (!eatOut.isAdded()) {
            transaction.add(R.id.fragment_container, eatOut, TAG_EATOUT);
        }
        for (Fragment f : fragmentManager.getFragments()) {
            if (f != null && f.isAdded()) {
                transaction.hide(f);
            }
        }
        transaction.show(eatOut);
        transaction.commit();
        currentFragment = eatOut;
        eatOut.refreshData();
    }

    public void navigateToLastTab() {
        if (lastTabFragment != null) {
            showFragment(lastTabFragment);
        } else {
            navigateToHome();
        }
    }

    public HeadFragment getHeadFragment() {
        return headFragment;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            boolean refreshNeeded = data.getBooleanExtra("refresh_needed", false);
            if (refreshNeeded) {
                refreshHomePageData();
            }
        }
    }

    private void refreshHomePageData() {
        if (headFragment != null) {
            headFragment.refreshCurrentFragmentData();
        }

        Fragment inventory = fragmentPool.get(TAG_INVENTORY);
        if (inventory instanceof InventoryFragment) {
            ((InventoryFragment) inventory).refreshInventoryData();
        }

        Fragment recipe = fragmentPool.get(TAG_RECIPE);
        if (recipe instanceof RecipeFragment) {
            ((RecipeFragment) recipe).refreshData();
        }

        Fragment todo = fragmentPool.get(TAG_TODO);
        if (todo instanceof TodoFragment) {
            ((TodoFragment) todo).refreshData();
        }
    }

    private void refreshAllFragmentsLoginState() {
        if (fragmentPool.get(TAG_MY) instanceof MyFragment) {
            Log.d("MainActivity", "MyFragment 将通过广播更新状态");
        }

        refreshHomePageData();
        DataRefreshBus.refreshAll();
    }

    private void syncBottomNavigationSelection(Fragment fragment) {
        if (mBottomNav == null || fragment == null) {
            return;
        }

        int itemId = getBottomNavigationItemId(fragment);
        if (itemId == 0) {
            itemId = R.id.nav_head;
        }
        if (mBottomNav.getSelectedItemId() == itemId) {
            return;
        }
        mBottomNav.getMenu().findItem(itemId).setChecked(true);
    }

    private int getBottomNavigationItemId(Fragment fragment) {
        if (fragment == headFragment) {
            return R.id.nav_head;
        } else if (fragment instanceof InventoryFragment) {
            return R.id.nav_inventory;
        } else if (fragment instanceof RecipeFragment) {
            return R.id.nav_recipe;
        } else if (fragment instanceof TodoFragment) {
            return R.id.nav_todo;
        } else if (fragment instanceof MyFragment) {
            return R.id.nav_my;
        }
        return 0;
    }

    @Override
    public void onBackPressed() {
        if (currentFragment instanceof EatOutFragment) {
            navigateToLastTab();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && "todo".equals(intent.getStringExtra("navigate_to"))) {
            mBottomNav.post(() -> mBottomNav.setSelectedItemId(R.id.nav_todo));
        }
        if (intent != null && intent.getStringExtra("username") != null) {
            DataRefreshBus.refreshAll();
            Fragment inventory = fragmentPool.get(TAG_INVENTORY);
            if (inventory instanceof InventoryFragment) {
                ((InventoryFragment) inventory).refreshInventoryData();
            }
            Fragment recipe = fragmentPool.get(TAG_RECIPE);
            if (recipe instanceof RecipeFragment) {
                ((RecipeFragment) recipe).refreshData();
            }
            Fragment todo = fragmentPool.get(TAG_TODO);
            if (todo instanceof TodoFragment) {
                ((TodoFragment) todo).refreshData();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PollingManager.getInstance().start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PollingManager.getInstance().stop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save current fragment tag
        if (currentFragment != null) {
            String tag = getFragmentTag(currentFragment);
            if (tag != null) {
                outState.putString(KEY_CURRENT_FRAGMENT_TAG, tag);
            }
        }
        // Save last tab fragment tag
        if (lastTabFragment != null) {
            String tag = getFragmentTag(lastTabFragment);
            if (tag != null) {
                outState.putString(KEY_LAST_TAB_FRAGMENT_TAG, tag);
            }
        }
    }

    private String getFragmentTag(Fragment fragment) {
        if (fragment == headFragment) return TAG_HEAD;
        if (fragment instanceof InventoryFragment) return TAG_INVENTORY;
        if (fragment instanceof RecipeFragment) return TAG_RECIPE;
        if (fragment instanceof EatOutFragment) return TAG_EATOUT;
        if (fragment instanceof TodoFragment) return TAG_TODO;
        if (fragment instanceof MyFragment) return TAG_MY;
        return null;
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(loginStateReceiver);
        // 接管 ChatRepository 生命周期：共享实例随 Activity 销毁时清理
        if (chatRepository != null) {
            try {
                chatRepository.cleanup();
            } catch (Exception e) {
                Log.e("MainActivity", "ChatRepository cleanup failed", e);
            }
            chatRepository = null;
        }
        super.onDestroy();
    }

    public ChatRepository getChatRepository() {
        return chatRepository;
    }
}
