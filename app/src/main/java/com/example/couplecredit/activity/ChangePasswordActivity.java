package com.example.couplecredit.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import com.example.couplecredit.function.UserInfoManager;

public class ChangePasswordActivity extends AppCompatActivity {
    
    private EditText etCurrentPassword;
    private EditText etNewPassword;
    private EditText etConfirmPassword;
    private Button btnChangePassword;
    private String username;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);
        
        // 使用UserInfoManager获取当前用户名
        username = UserInfoManager.getCurrentUsername(this);
        
        if (username == null) {
            Toast.makeText(this, "用户信息获取失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        initViews();
        setupClickListeners();
    }
    
    private void initViews() {
        etCurrentPassword = findViewById(R.id.et_current_password);
        etNewPassword = findViewById(R.id.et_new_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        btnChangePassword = findViewById(R.id.btn_change_password);
    }
    
    private void setupClickListeners() {
        btnChangePassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changePassword();
            }
        });
    }
    
    private void changePassword() {
        String currentPassword = etCurrentPassword.getText().toString().trim();
        String newPassword = etNewPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();
        
        // 输入验证
        if (TextUtils.isEmpty(currentPassword)) {
            Toast.makeText(this, "请输入当前密码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(newPassword)) {
            Toast.makeText(this, "请输入新密码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (newPassword.length() < 6) {
            Toast.makeText(this, "新密码长度不能少于6位", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (TextUtils.isEmpty(confirmPassword)) {
            Toast.makeText(this, "请确认新密码", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!newPassword.equals(confirmPassword)) {
            Toast.makeText(this, "两次输入的新密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (currentPassword.equals(newPassword)) {
            Toast.makeText(this, "新密码不能与当前密码相同", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 禁用按钮防止重复提交
        btnChangePassword.setEnabled(false);
        btnChangePassword.setText("修改中...");
        
        // 调用数据库修改密码
        MySQLDatabaseHelper.updatePassword(username, currentPassword, newPassword, new MySQLDatabaseHelper.DatabaseCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ChangePasswordActivity.this, "密码修改成功", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ChangePasswordActivity.this, error, Toast.LENGTH_SHORT).show();
                        btnChangePassword.setEnabled(true);
                        btnChangePassword.setText("修改密码");
                    }
                });
            }
        });
    }
}