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
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.database.DatabaseInitializer;
import com.example.couplecredit.fragment.AddBillFragment;
import com.example.couplecredit.fragment.HeadFragment;
import com.example.couplecredit.fragment.MyFragment;
import com.example.couplecredit.fragment.ReportFragment;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.repository.ChatRepository;
import com.github.mikephil.charting.utils.Utils;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private FragmentManager fragmentManager;
    private HeadFragment headFragment;
    private AddBillFragment addBillFragment;
    private ReportFragment reportFragment;
    private MyFragment myFragment;
    private BottomNavigationView mBottomNav;
    private Fragment currentFragment;
    private ChatRepository chatRepository;

    private final BroadcastReceiver loginStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("MainActivity", "收到登录状态变化广播: " + intent.getAction());
            if ("com.example.couplecredit.USER_LOGIN".equals(intent.getAction())) {
                navigateToHome();
            }
            refreshAllFragmentsLoginState();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Utils.init(this);
        DatabaseInitializer.initializeConnectionPoolAsync("MainActivity");
        Log.d("MainActivity", "数据库连接池异步初始化已启动");

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
            addBillFragment = new AddBillFragment();
            reportFragment = new ReportFragment();
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
            addBillFragment = (AddBillFragment) fragmentManager.findFragmentByTag("addBill");
            reportFragment = (ReportFragment) fragmentManager.findFragmentByTag("report");
            myFragment = (MyFragment) fragmentManager.findFragmentByTag("my");

            for (Fragment fragment : fragmentManager.getFragments()) {
                if (fragment != null && fragment.isVisible()) {
                    currentFragment = fragment;
                    break;
                }
            }
        }

        mBottomNav = findViewById(R.id.bottom_nav);
        mBottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_head) {
                showFragment(headFragment);
                return true;
            } else if (itemId == R.id.nav_addbill) {
                showFragment(addBillFragment);
                return true;
            } else if (itemId == R.id.nav_report) {
                showFragment(reportFragment);
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
            syncBottomNavigationSelection(currentFragment);
        }

        // 注册登录状态变化广播接收器
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
    }

    public void navigateToHome() {
        if (mBottomNav != null && mBottomNav.getSelectedItemId() != R.id.nav_head) {
            mBottomNav.setSelectedItemId(R.id.nav_head);
        } else {
            showFragment(headFragment);
        }
    }

    public HeadFragment getHeadFragment() {
        return headFragment;
    }

    public ReportFragment getReportFragment() {
        return reportFragment;
    }

    private void initAllFragments() {
        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        transaction.add(R.id.fragment_container, headFragment, "head");
        transaction.add(R.id.fragment_container, addBillFragment, "addBill");
        transaction.add(R.id.fragment_container, reportFragment, "report");
        transaction.add(R.id.fragment_container, myFragment, "my");
        transaction.hide(headFragment);
        transaction.hide(addBillFragment);
        transaction.hide(reportFragment);
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

        if (reportFragment != null) {
            reportFragment.refreshChartData();
        }

        Log.d("MainActivity", "首页数据刷新完成");
    }

    /**
     * 刷新所有Fragment的登录状态
     */
    private void refreshAllFragmentsLoginState() {
        // MyFragment 已通过广播自行刷新登录状态，这里只刷新依赖登录态的数据
        if (myFragment != null) {
            Log.d("MainActivity", "MyFragment 将通过广播更新状态");
        }

        refreshHomePageData();
        Log.d("MainActivity", "所有Fragment登录状态已刷新");
    }

    private void syncBottomNavigationSelection(Fragment fragment) {
        if (mBottomNav == null || fragment == null) {
            return;
        }

        int itemId = getBottomNavigationItemId(fragment);
        if (itemId == 0 || mBottomNav.getSelectedItemId() == itemId) {
            return;
        }

        mBottomNav.getMenu().findItem(itemId).setChecked(true);
    }

    private int getBottomNavigationItemId(Fragment fragment) {
        if (fragment == headFragment) {
            return R.id.nav_head;
        } else if (fragment == addBillFragment) {
            return R.id.nav_addbill;
        } else if (fragment == reportFragment) {
            return R.id.nav_report;
        } else if (fragment == myFragment) {
            return R.id.nav_my;
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(loginStateReceiver);
        super.onDestroy();
    }
}
