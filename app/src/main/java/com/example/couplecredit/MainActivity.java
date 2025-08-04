package com.example.couplecredit;

import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    
    private FragmentManager fragmentManager;
    private HeadFragment headFragment;
    private PlanFragment planFragment;
    private ReportFragment reportFragment;
    private MyFragment myFragment;
    
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
        
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.nav_head); // 设置默认选中项
        
        // 为底部导航栏设置WindowInsets处理，让它延伸到屏幕底部
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 为底部导航栏添加底部padding，确保内容不被系统导航栏遮挡
            v.setPadding(0, 0, 0, systemBars.bottom);
            return insets;
        });
        
        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_head) {
                showFragment(headFragment);
                return true;
            } else if (item.getItemId() == R.id.nav_plan) {
                showFragment(planFragment);
                return true;
            } else if (item.getItemId() == R.id.nav_report) {
                showFragment(reportFragment);
                return true;
            } else if (item.getItemId() == R.id.nav_my) {
                showFragment(myFragment);
                return true;
            }
            return false;
        });
    }
    
    private void showFragment(Fragment fragment) {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }
}