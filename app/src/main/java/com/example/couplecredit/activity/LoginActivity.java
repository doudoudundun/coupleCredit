package com.example.couplecredit.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

/**
 * 登录页面Activity
 * 提供用户登录功能界面
 */
public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    
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
        
        AuthApiClient.login(LoginActivity.this, username, password, new AuthApiClient.Callback() {
            @Override
            public void onSuccess(AuthApiModels.AuthResponse response) {
                AuthApiModels.AuthSuccessData userData = response.data;
                Log.d(TAG, "登录接口返回: message=" + response.message
                        + ", username=" + (userData != null ? userData.username : "null")
                        + ", userId=" + (userData != null ? userData.userId : -1));
                if (userData == null || userData.username == null || userData.userId <= 0) {
                    Toast.makeText(LoginActivity.this, "登录响应缺少用户信息，请重试", Toast.LENGTH_SHORT).show();
                    return;
                }

                boolean saved = UserInfoManager.saveUserInfo(LoginActivity.this, userData.username, userData.userId);
                if (!saved) {
                    Toast.makeText(LoginActivity.this, "登录状态保存失败，请重试", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 持久化 JWT token，后续所有请求自动携带
                UserInfoManager.saveTokens(LoginActivity.this, userData.accessToken, userData.refreshToken);

                String savedUsername = UserInfoManager.getCurrentUsername(LoginActivity.this);
                int savedUserId = UserInfoManager.getCurrentUserId(LoginActivity.this);
                boolean loggedIn = UserInfoManager.isUserLoggedIn(LoginActivity.this);
                Log.d(TAG, "登录状态回读: username=" + savedUsername + ", userId=" + savedUserId + ", loggedIn=" + loggedIn);
                if (!loggedIn || savedUserId <= 0 || savedUsername == null) {
                    Toast.makeText(LoginActivity.this, "登录状态校验失败，请重试", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent broadcastIntent = new Intent("com.example.couplecredit.USER_LOGIN");
                LocalBroadcastManager.getInstance(LoginActivity.this).sendBroadcast(broadcastIntent);

                Toast.makeText(LoginActivity.this, "登录成功！", Toast.LENGTH_SHORT).show();
                Intent resultIntent = new Intent();
                resultIntent.putExtra("username", userData.username);
                resultIntent.putExtra("id", String.valueOf(userData.userId));
                setResult(Activity.RESULT_OK, resultIntent);

                Intent mainIntent = new Intent(LoginActivity.this, MainActivity.class);
                mainIntent.putExtra("username", userData.username);
                mainIntent.putExtra("id", String.valueOf(userData.userId));
                mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(mainIntent);
                finish();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(LoginActivity.this, "登录失败：" + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
