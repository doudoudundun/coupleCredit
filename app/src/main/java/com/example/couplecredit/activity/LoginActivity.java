package com.example.couplecredit.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.couplecredit.R;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import android.content.SharedPreferences;

/**
 * 登录页面Activity
 * 提供用户登录功能界面
 */
public class LoginActivity extends AppCompatActivity {
    
    private EditText etUsername;
    private EditText etPassword;
    private Button btnLogin;
    private TextView tvRegisterHint;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        
        // 初始化UI组件
        initViews();
        
        // 设置事件监听器
        setupListeners();
    }
    
    /**
     * 初始化UI组件
     */
    private void initViews() {
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        tvRegisterHint = findViewById(R.id.tv_register_hint);
    }
    
    /**
     * 设置事件监听器
     */
    private void setupListeners() {
        // 登录按钮点击事件
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performLogin();
            }
        });
        
        // 注册提示点击事件
        tvRegisterHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳转到注册页面
                Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
                startActivity(intent);
            }
        });
    }
    
    /**
     * 执行登录操作
     */
    private void performLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        
        // 输入验证
        if (username.isEmpty()) {
            Toast.makeText(this, "请输入账号", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 使用MySQLDatabaseHelper进行登录验证
        MySQLDatabaseHelper.loginUser(username, password, new MySQLDatabaseHelper.LoginCallback() {
            @Override
            public void onLoginSuccess(String message, String userInfo) {
                runOnUiThread(() -> {
                    // 保存用户登录状态到SharedPreferences
                    SharedPreferences sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString("username", username);
                    editor.putString("id", userInfo);
                    editor.putBoolean("isLoggedIn", true);
                    editor.apply();
                    
                    // 发送登录成功广播
                    Intent broadcastIntent = new Intent("com.example.couplecredit.USER_LOGIN");
                    LocalBroadcastManager.getInstance(LoginActivity.this).sendBroadcast(broadcastIntent);
                    
                    Toast.makeText(LoginActivity.this, "登录成功！", Toast.LENGTH_SHORT).show();
                    // 登录成功，跳转到主界面
                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    intent.putExtra("username", username);
                    intent.putExtra("id", userInfo);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onLoginError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(LoginActivity.this, "登录失败：" + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}