package com.example.couplecredit.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.couplecredit.R;
import com.example.couplecredit.database.DatabaseInitializer;
import com.example.couplecredit.fragment.AddBillFragment;
import com.example.couplecredit.fragment.HeadFragment;
import com.example.couplecredit.fragment.MyFragment;
import com.example.couplecredit.fragment.ReportFragment;
import com.example.couplecredit.function.UserInfoManager;
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
            showFragment(headFragment);
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
        mBottomNav.setSelectedItemId(R.id.nav_head);

        ViewCompat.setOnApplyWindowInsetsListener(mBottomNav, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom);
            return insets;
        });
    }

    public void showFragment(Fragment fragment) {
        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);

        if (currentFragment != null) {
            transaction.hide(currentFragment);
        } else {
            for (Fragment existingFragment : fragmentManager.getFragments()) {
                if (existingFragment != null && existingFragment.getView() != null && existingFragment.isVisible()) {
                    transaction.hide(existingFragment);
                }
            }
        }

        transaction.show(fragment);
        currentFragment = fragment;
        transaction.commit();
    }

    public HeadFragment getHeadFragment() {
        return headFragment;
    }

    public ReportFragment getReportFragment() {
        return reportFragment;
    }

    private void initAllFragments() {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.add(R.id.fragment_container, headFragment, "head");
        transaction.add(R.id.fragment_container, addBillFragment, "addBill");
        transaction.add(R.id.fragment_container, reportFragment, "report");
        transaction.add(R.id.fragment_container, myFragment, "my");
        transaction.hide(headFragment);
        transaction.hide(addBillFragment);
        transaction.hide(reportFragment);
        transaction.hide(myFragment);
        transaction.commit();
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
}
