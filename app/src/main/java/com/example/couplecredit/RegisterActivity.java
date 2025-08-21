package com.example.couplecredit;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 注册页面Activity
 * 提供用户注册功能界面
 */
public class RegisterActivity extends AppCompatActivity {
    
    private EditText etUsername;
    private EditText etPassword;
    private EditText etConfirmPassword;
    private Button btnRegister;
    private TextView tvLoginHint;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        
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
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        btnRegister = findViewById(R.id.btn_register);
        tvLoginHint = findViewById(R.id.tv_login_hint);
    }
    
    /**
     * 设置事件监听器
     */
    private void setupListeners() {
        // 注册按钮点击事件
        btnRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performRegister();
            }
        });
        
        // 登录提示点击事件
        tvLoginHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳转到登录页面
                Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }
    
    /**
     * 执行注册操作
     */
    private void performRegister() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();
        
        // 输入验证
        if (username.isEmpty()) {
            Toast.makeText(this, "请输入账号", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (confirmPassword.isEmpty()) {
            Toast.makeText(this, "请确认密码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!password.equals(confirmPassword)) {
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (password.length() < 6) {
            Toast.makeText(this, "密码长度不能少于6位", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 暂时显示注册信息，实际注册功能待实现
        Toast.makeText(this, "注册功能暂未实现\n账号: " + username + "\n密码: " + password, Toast.LENGTH_LONG).show();
        
        // 模拟注册成功，跳转到登录页面
        // Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
        // startActivity(intent);
        // finish();
    }
}