package com.example.couplecredit;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
                // 暂时显示提示信息，后续可跳转到注册页面
                Toast.makeText(LoginActivity.this, "注册功能暂未开放", Toast.LENGTH_SHORT).show();
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
        
        // 暂时显示登录信息，实际登录功能待实现
        Toast.makeText(this, "登录功能暂未实现\n账号: " + username + "\n密码: " + password, Toast.LENGTH_LONG).show();
        
        // 模拟登录成功，关闭当前页面
        // finish();
    }
}