package com.example.couplecredit;

import static com.transsion.widgetslib.util.Utils.isGestureNavigationBarOn;

import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.transsion.widgetslib.widget.FootOperationBar;

public class MainActivity extends AppCompatActivity {

    private FragmentManager fragmentManager;
    private HeadFragment headFragment;
    private PlanFragment planFragment;
    private ReportFragment reportFragment;
    private MyFragment myFragment;
    private FootOperationBar mFootOptBar;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 只设置左、上、右的padding，不设置底部padding，让底部导航栏延伸到屏幕底部
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        // 初始化Fragment管理器和Fragment实例
        fragmentManager = getSupportFragmentManager();
        headFragment = new HeadFragment();
        planFragment = new PlanFragment();
        reportFragment = new ReportFragment();
        myFragment = new MyFragment();

        // 默认显示首页Fragment
        showFragment(headFragment);
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
                            showFragment(planFragment);
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
            v.setPadding(0, 0, 0, systemBars.bottom);
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

    private void showFragment (Fragment fragment){
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }

}