package com.example.couplecredit.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.DataRefreshBus;
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

public class MainActivity extends AppCompatActivity {

    private FragmentManager fragmentManager;
    private HeadFragment headFragment;
    private InventoryFragment inventoryFragment;
    private RecipeFragment recipeFragment;
    private EatOutFragment eatOutFragment;
    private TodoFragment todoFragment;
    private MyFragment myFragment;
    private BottomNavigationView mBottomNav;
    private Fragment currentFragment;
    private Fragment lastTabFragment;
    private ChatRepository chatRepository;

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

        Utils.init(this);

        new Thread(this::initializeChatData, "ChatRepositoryInitializer").start();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

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

        if (savedInstanceState == null) {
            headFragment = new HeadFragment();
            inventoryFragment = new InventoryFragment();
            recipeFragment = new RecipeFragment();
            eatOutFragment = new EatOutFragment();
            todoFragment = new TodoFragment();
            myFragment = new MyFragment();

            if (username != null && id != null) {
                Bundle bundle = new Bundle();
                bundle.putString("username", username);
                bundle.putString("id", id);
                myFragment.setArguments(bundle);
            }

            initAllFragments();
        } else {
            headFragment = (HeadFragment) fragmentManager.findFragmentByTag("head");
            inventoryFragment = (InventoryFragment) fragmentManager.findFragmentByTag("inventory");
            recipeFragment = (RecipeFragment) fragmentManager.findFragmentByTag("recipe");
            eatOutFragment = (EatOutFragment) fragmentManager.findFragmentByTag("eatOut");
            todoFragment = (TodoFragment) fragmentManager.findFragmentByTag("todo");
            myFragment = (MyFragment) fragmentManager.findFragmentByTag("my");

            // Restore current fragment from saved state
            String currentTag = savedInstanceState.getString(KEY_CURRENT_FRAGMENT_TAG, "head");
            currentFragment = fragmentManager.findFragmentByTag(currentTag);

            // If currentFragment is still null, default to headFragment
            if (currentFragment == null) {
                currentFragment = headFragment;
            }

            // Restore lastTabFragment
            String lastTabTag = savedInstanceState.getString(KEY_LAST_TAB_FRAGMENT_TAG, null);
            if (lastTabTag != null) {
                lastTabFragment = fragmentManager.findFragmentByTag(lastTabTag);
            }
        }

        mBottomNav = findViewById(R.id.bottom_nav);
        mBottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_head) {
                showFragment(headFragment);
                return true;
            } else if (itemId == R.id.nav_inventory) {
                showFragment(inventoryFragment);
                return true;
            } else if (itemId == R.id.nav_recipe) {
                showFragment(recipeFragment);
                return true;
            } else if (itemId == R.id.nav_todo) {
                showFragment(todoFragment);
                return true;
            } else if (itemId == R.id.nav_my) {
                showFragment(myFragment);
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

    private boolean isOneOfTopFragments(Fragment fragment) {
        return fragment == headFragment || fragment == inventoryFragment
                || fragment == recipeFragment || fragment == eatOutFragment
                || fragment == todoFragment || fragment == myFragment;
    }

    public void showFragment(Fragment fragment) {
        if (fragment == null) {
            return;
        }
        if (fragment == currentFragment) {
            syncBottomNavigationSelection(fragment);
            return;
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);

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

        if (fragment == inventoryFragment) {
            inventoryFragment.refreshInventoryData();
            PollingManager.getInstance().setRefreshAction(inventoryFragment::refreshInventoryData);
        } else if (fragment == recipeFragment) {
            recipeFragment.refreshData();
            PollingManager.getInstance().setRefreshAction(recipeFragment::refreshData);
        } else if (fragment == eatOutFragment) {
            eatOutFragment.refreshData();
            PollingManager.getInstance().setRefreshAction(eatOutFragment::refreshData);
        } else if (fragment == todoFragment) {
            todoFragment.refreshData();
            PollingManager.getInstance().setRefreshAction(todoFragment::refreshData);
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
            showFragment(inventoryFragment);
        }
    }

    public void showEatOutFragment() {
        if (eatOutFragment == null) return;
        if (eatOutFragment == currentFragment) return;
        lastTabFragment = currentFragment;

        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        for (Fragment f : fragmentManager.getFragments()) {
            if (f != null && f.isAdded()) {
                transaction.hide(f);
            }
        }
        transaction.show(eatOutFragment);
        transaction.commit();
        currentFragment = eatOutFragment;
        eatOutFragment.refreshData();
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

    private void initAllFragments() {
        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        transaction.add(R.id.fragment_container, headFragment, "head");
        transaction.add(R.id.fragment_container, inventoryFragment, "inventory");
        transaction.add(R.id.fragment_container, recipeFragment, "recipe");
        transaction.add(R.id.fragment_container, eatOutFragment, "eatOut");
        transaction.add(R.id.fragment_container, todoFragment, "todo");
        transaction.add(R.id.fragment_container, myFragment, "my");
        transaction.hide(headFragment);
        transaction.hide(inventoryFragment);
        transaction.hide(recipeFragment);
        transaction.hide(eatOutFragment);
        transaction.hide(todoFragment);
        transaction.hide(myFragment);
        transaction.commitNow();
        currentFragment = null;
    }

    private void initializeChatData() {
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
    }

    public ChatRepository getChatRepository() {
        return chatRepository;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
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

        if (inventoryFragment != null) {
            inventoryFragment.refreshInventoryData();
        }

        if (recipeFragment != null) {
            recipeFragment.refreshData();
        }

        if (todoFragment != null) {
            todoFragment.refreshData();
        }

    }

    private void refreshAllFragmentsLoginState() {
        if (myFragment != null) {
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
        } else if (fragment == inventoryFragment) {
            return R.id.nav_inventory;
        } else if (fragment == recipeFragment) {
            return R.id.nav_recipe;
        } else if (fragment == todoFragment) {
            return R.id.nav_todo;
        } else if (fragment == myFragment) {
            return R.id.nav_my;
        }
        return 0;
    }

    @Override
    public void onBackPressed() {
        if (currentFragment == eatOutFragment) {
            navigateToLastTab();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getStringExtra("username") != null) {
            DataRefreshBus.refreshAll();
            if (inventoryFragment != null) inventoryFragment.refreshInventoryData();
            if (recipeFragment != null) recipeFragment.refreshData();
            if (todoFragment != null) todoFragment.refreshData();
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
        if (fragment == headFragment) return "head";
        if (fragment == inventoryFragment) return "inventory";
        if (fragment == recipeFragment) return "recipe";
        if (fragment == eatOutFragment) return "eatOut";
        if (fragment == todoFragment) return "todo";
        if (fragment == myFragment) return "my";
        return null;
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(loginStateReceiver);
        super.onDestroy();
    }
}
