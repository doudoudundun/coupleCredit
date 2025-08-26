package com.example.couplecredit.activity;

import static com.transsion.widgetslib.util.Utils.isGestureNavigationBarOn;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

import com.example.couplecredit.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.couplecredit.fragment.AddBillFragment;
import com.example.couplecredit.fragment.HeadFragment;
import com.example.couplecredit.fragment.MyFragment;
import com.example.couplecredit.fragment.ReportFragment;
import com.example.couplecredit.function.UserInfoManager;
import com.transsion.widgetslib.widget.FootOperationBar;
import com.github.mikephil.charting.utils.Utils;
import android.content.SharedPreferences;

public class MainActivity extends AppCompatActivity {

    private FragmentManager fragmentManager;
    private HeadFragment headFragment;

    private AddBillFragment addBillFragment;
    private ReportFragment reportFragment;
    private MyFragment myFragment;
    private FootOperationBar mFootOptBar;
    private Fragment currentFragment;



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        // 初始化MPAndroidChart的Utils
        Utils.init(this);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 只设置左、上、右的padding，不设置底部padding，让底部导航栏延伸到屏幕底部
            v.setPadding(systemBars.left, 0, systemBars.right, 0);
            return insets;
        });

        // 初始化Fragment管理器
        fragmentManager = getSupportFragmentManager();
        
        // 获取用户信息（优先从Intent，其次从UserInfoManager）
        Intent intent = getIntent();
        String username = intent.getStringExtra("username");
        String id = intent.getStringExtra("id");
        
        // 如果Intent中没有用户信息，从UserInfoManager读取
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
            // 首次启动：创建新的Fragment实例
            headFragment = new HeadFragment();
            addBillFragment = new AddBillFragment();
            reportFragment = new ReportFragment();
            myFragment = new MyFragment();
            
            // 如果有用户信息，传递给MyFragment
            if (username != null && id != null) {
                Bundle bundle = new Bundle();
                bundle.putString("username", username);
                bundle.putString("id", id);
                myFragment.setArguments(bundle);
            }
            
            // 预初始化所有Fragment，避免运行时空指针异常
            initAllFragments();
            
            // 默认显示首页Fragment
            showFragment(headFragment);
        } else {
            // 恢复状态：复用已有的Fragment实例
            headFragment = (HeadFragment) fragmentManager.findFragmentByTag("head");
            addBillFragment = (AddBillFragment) fragmentManager.findFragmentByTag("addBill");
            reportFragment = (ReportFragment) fragmentManager.findFragmentByTag("report");
            myFragment = (MyFragment) fragmentManager.findFragmentByTag("my");
            
            // 恢复currentFragment（找当前可见的Fragment）
            for (Fragment f : fragmentManager.getFragments()) {
                if (f != null && f.isVisible()) {
                    currentFragment = f;
                    break;
                }
            }
        }

        mFootOptBar = (FootOperationBar) findViewById(R.id.bottom_nav);
        

        mFootOptBar.inflateMenu(R.menu.bottom_nav_menu);
        //mFootOptBar.setLandscape(isFootOperationLandscape());
        //BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        //mFootOptBar.setSelectedItemId(R.id.nav_head); // 设置默认选中项
        mFootOptBar.setOnFootOptBarClickListener(
                new FootOperationBar.OnFootOptBarClickListener() {
                    @Override
                    public void onItemClick(int index) {
                        if (index == 0) {
                            showFragment(headFragment);
                        } else if (index == 1) {
                            showFragment(addBillFragment);
                        } else if (index == 2) {
                            showFragment(reportFragment);
                        } else if (index == 3) {
                            showFragment(myFragment);
                        }
                    }
                });
        mFootOptBar.setItemSelectState(0);
        // 为底部导航栏设置WindowInsets处理，让它延伸到屏幕底部
        ViewCompat.setOnApplyWindowInsetsListener(mFootOptBar, (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 为底部导航栏添加底部padding，确保内容不被系统导航栏遮挡
            v.setPadding(-20, 0, -20, 10);
            return insets;
        });

        boolean isGestureNavigationBarOn = isGestureNavigationBarOn(this);
        //沉浸式系统方案
        if (isGestureNavigationBarOn) {
            // 设置全屏显示
            int option = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            //int option = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            //        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            getWindow().getDecorView().setSystemUiVisibility(option);

            // 设置状态栏背景色
            getWindow().setStatusBarColor(Color.TRANSPARENT);

            // 设置导航栏背景色为透明色
            getWindow().setNavigationBarColor(Color.TRANSPARENT);

            // 内容是否超出状态栏和导航栏显示，false表示可以超出
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
            }

            // 根据WindowInsets设置布局边距，防止布局跟状态栏或导航栏重叠
            getWindow().getDecorView().setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Insets systemWindow = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout());  //获取systemWindow
                        View view = findViewById(android.R.id.content);
                        view.setPadding(0, systemWindow.top, 0, 0);  //设置状态栏边距防止跟状态栏重叠
                        return insets;
                    }
                    return insets;
                }
            });
        }
    }

    public void showFragment(Fragment fragment) {
        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        
        // 隐藏当前Fragment
        if (currentFragment != null) {
            transaction.hide(currentFragment);
        } else {
            // 兜底：全部隐藏一遍（只针对顶层容器的fragment）
            for (Fragment f : fragmentManager.getFragments()) {
                if (f != null && f.getView() != null && f.isVisible()) {
                    transaction.hide(f);
                }
            }
        }
        
        // 显示目标Fragment（所有Fragment已在initAllFragments中预添加）
        transaction.show(fragment);
        
        currentFragment = fragment;
        transaction.commit();
    }
    
    // 提供获取HeadFragment的方法
    public HeadFragment getHeadFragment() {
        return headFragment;
    }
    
    // 提供获取ReportFragment的方法
    public ReportFragment getReportFragment() {
        return reportFragment;
    }

    /**
     * 预初始化所有Fragment，确保它们在应用启动时就完成初始化
     */
    private void initAllFragments() {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        
        // 添加所有Fragment但设为隐藏状态
        transaction.add(R.id.fragment_container, headFragment, "head");
        transaction.add(R.id.fragment_container, addBillFragment, "addBill");
        transaction.add(R.id.fragment_container, reportFragment, "report");
        transaction.add(R.id.fragment_container, myFragment, "my");
        
        // 隐藏所有Fragment
        transaction.hide(headFragment);
        transaction.hide(addBillFragment);
        transaction.hide(reportFragment);
        transaction.hide(myFragment);
        
        transaction.commit();
    }
}