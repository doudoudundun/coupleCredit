package com.example.couplecredit.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.couplecredit.R;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import android.content.SharedPreferences;

public class UserSettingsActivity extends AppCompatActivity {

    // UI组件声明
    private TextView tvUserInfo;           // 用户信息显示
    private LinearLayout llCoupleBinding;  // 情侣绑定选项
    private LinearLayout llChangePassword; // 修改密码选项
    private LinearLayout llLogout;         // 注销用户选项
    
    // 用户信息
    private String username;
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_settings);

        // 获取传递的用户信息
        Intent intent = getIntent();
        username = intent.getStringExtra("username");
        userId = intent.getStringExtra("id");
        
        // 初始化UI组件
        initViews();
        
        // 设置点击事件
        setupListeners();
        
        // 更新用户信息显示
        updateUserInfo();
    }
    
    /**
     * 初始化UI组件
     */
    private void initViews() {
        tvUserInfo = findViewById(R.id.tv_user_info);
        llCoupleBinding = findViewById(R.id.ll_couple_binding);
        llChangePassword = findViewById(R.id.ll_change_password);
        llLogout = findViewById(R.id.ll_logout);
    }
    
    /**
     * 设置点击事件监听器
     */
    private void setupListeners() {
        // 情侣绑定点击事件
        llCoupleBinding.setOnClickListener(v -> {
            Intent intent = new Intent(UserSettingsActivity.this, com.example.couplecredit.CoupleBindingActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });
        
        // 修改密码点击事件
        llChangePassword.setOnClickListener(v -> {
            Intent intent = new Intent(UserSettingsActivity.this, ChangePasswordActivity.class);
            startActivity(intent);
        });
        
        // 注销用户点击事件
        llLogout.setOnClickListener(v -> {
            showLogoutDialog();
        });
    }
    
    /**
     * 更新用户信息显示
     */
    private void updateUserInfo() {
        if (username != null && userId != null) {
            String userInfoText = "用户名: " + username + "\nID: " + userId;
            tvUserInfo.setText(userInfoText);
        }
    }
    
    /**
     * 显示注销确认对话框
     */
    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("确认注销")
                .setMessage("确定要注销当前账户吗？\n你的所有数据都会被清空，无法恢复")
                .setPositiveButton("确定", (dialog, which) -> {
                    // 执行注销操作
                    performLogout();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 执行注销操作
     */
    private void performLogout() {
        if (username == null || username.isEmpty()) {
            Toast.makeText(this, "用户信息异常，无法注销", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示进度提示
        Toast.makeText(this, "正在注销账户...", Toast.LENGTH_SHORT).show();
        
        // 调用数据库删除用户
        MySQLDatabaseHelper.deleteUser(username, new MySQLDatabaseHelper.DatabaseCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    // 清空SharedPreferences中的用户登录状态
                    SharedPreferences sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.clear();
                    editor.apply();
                    
                    Toast.makeText(UserSettingsActivity.this, "账户注销成功", Toast.LENGTH_SHORT).show();
                    
                    // 返回登录界面
                    Intent intent = new Intent(UserSettingsActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "注销失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}