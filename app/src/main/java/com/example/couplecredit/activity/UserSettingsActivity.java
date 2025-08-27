package com.example.couplecredit.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.database.CoupleRelationshipHelper;

public class UserSettingsActivity extends AppCompatActivity {

    // UI组件声明
    private TextView tvUserInfo;           // 用户信息显示
    private LinearLayout llCoupleBinding;  // 情侣绑定选项
    private LinearLayout llChangeNickname; // 修改昵称选项
    private LinearLayout llChangePassword; // 修改密码选项
    private LinearLayout llSignOut;        // 退出登录选项
    private LinearLayout llLogout;         // 注销用户选项
    private LinearLayout llCoupleInfo, llUnbindCouple;
    private TextView tvCoupleInfo, tvCoupleHint;
    private ImageView ivUserAvatar, ivCoupleAvatar;
    
    // 用户信息
    private String username;
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_settings);

        // 优先从Intent获取用户信息，否则从UserInfoManager获取
        Intent intent = getIntent();
        username = intent.getStringExtra("username");
        userId = intent.getStringExtra("id");
        
        // 如果Intent中没有用户信息，从UserInfoManager获取
        if (username == null || userId == null) {
            username = UserInfoManager.getCurrentUsername(this);
            int userIdInt = UserInfoManager.getCurrentUserId(this);
            if (userIdInt != -1) {
                userId = String.valueOf(userIdInt);
            }
        }
        
        // 初始化UI组件
        initViews();
        
        // 设置点击事件
        setupListeners();
        
        // 更新用户信息显示
        updateUserInfo();
        
        // 加载情侣信息
        loadCoupleInfo();
    }
    
    /**
     * 初始化UI组件
     */
    private void initViews() {
        tvUserInfo = findViewById(R.id.tv_user_info);
        ivUserAvatar = findViewById(R.id.iv_user_avatar);
        tvCoupleHint = findViewById(R.id.tv_couple_hint);
        llCoupleInfo = findViewById(R.id.ll_couple_info);
        tvCoupleInfo = findViewById(R.id.tv_couple_info);
        ivCoupleAvatar = findViewById(R.id.iv_couple_avatar);
        llUnbindCouple = findViewById(R.id.ll_unbind_couple);
        llCoupleBinding = findViewById(R.id.ll_couple_binding);
        llChangeNickname = findViewById(R.id.ll_change_nickname);
        llChangePassword = findViewById(R.id.ll_change_password);
        llSignOut = findViewById(R.id.ll_sign_out);
        llLogout = findViewById(R.id.ll_logout);
    }
    
    /**
     * 设置点击事件监听器
     */
    private void setupListeners() {
        // 情侣绑定点击事件
        llCoupleBinding.setOnClickListener(v -> {
            Intent intent = new Intent(UserSettingsActivity.this, CoupleBindingActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });
        
        // 修改昵称点击事件
        llChangeNickname.setOnClickListener(v -> showChangeNicknameDialog());
        
        // 修改密码点击事件
        llChangePassword.setOnClickListener(v -> {
            Intent intent = new Intent(UserSettingsActivity.this, ChangePasswordActivity.class);
            intent.putExtra("username", username);
            startActivity(intent);
        });
        
        // 退出登录点击事件
        llSignOut.setOnClickListener(v -> showSignOutDialog());
        
        // 解绑情侣关系点击事件
        llUnbindCouple.setOnClickListener(v -> showUnbindDialog());
        
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
            // 获取用户昵称，如果有昵称则显示昵称，否则显示用户名
            MySQLDatabaseHelper.getUserNickname(username, new MySQLDatabaseHelper.UserNicknameCallback() {
                @Override
                public void onSuccess(String nickname) {
                    runOnUiThread(() -> {
                        String displayName = (nickname != null && !nickname.trim().isEmpty()) ? nickname : username;
                        String userInfoText = displayName + "\nID: " + userId;
                        tvUserInfo.setText(userInfoText);
                    });
                }
                
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        // 查询昵称失败，使用用户名显示
                        String userInfoText = "用户名: " + username + "\nID: " + userId;
                        tvUserInfo.setText(userInfoText);
                    });
                }
            });
        }
    }
    
    /**
     * 加载情侣信息
     */
    private void loadCoupleInfo() {
        CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
        int userIdInt = Integer.parseInt(userId);
        coupleHelper.getCoupleInfo(userIdInt, new CoupleRelationshipHelper.CoupleInfoCallback() {
            @Override
            public void onCoupleFound(int coupleId, String coupleName, String coupleNickname) {
                runOnUiThread(() -> {
                    // 显示情侣信息和提示文本，优先显示昵称
                    String displayName = (coupleNickname != null && !coupleNickname.trim().isEmpty()) ? coupleNickname : coupleName;
                    tvCoupleInfo.setText(displayName);
                    tvCoupleHint.setVisibility(View.VISIBLE);
                    llCoupleInfo.setVisibility(View.VISIBLE);
                    llUnbindCouple.setVisibility(View.VISIBLE);
                    // 隐藏情侣绑定选项
                    llCoupleBinding.setVisibility(View.GONE);
                });
            }

            @Override
            public void onNoCoupleFound() {
                runOnUiThread(() -> {
                    // 没有情侣关系，隐藏情侣信息，显示情侣绑定选项
                    tvCoupleHint.setVisibility(View.GONE);
                    llCoupleInfo.setVisibility(View.GONE);
                    llUnbindCouple.setVisibility(View.GONE);
                    llCoupleBinding.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    // 出错时也隐藏情侣信息，显示情侣绑定选项
                    tvCoupleHint.setVisibility(View.GONE);
                    llCoupleInfo.setVisibility(View.GONE);
                    llUnbindCouple.setVisibility(View.GONE);
                    llCoupleBinding.setVisibility(View.VISIBLE);
                });
            }
        });
    }
    
    /**
     * 显示解绑情侣关系确认对话框
     */
    private void showUnbindDialog() {
        new AlertDialog.Builder(this)
                .setTitle("解绑情侣关系")
                .setMessage("确定要解绑情侣关系吗？解绑后将无法查看对方的账单记录。")
                .setPositiveButton("确定", (dialog, which) -> performUnbind())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示退出登录确认对话框
     */
    private void showSignOutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("确认退出")
                .setMessage("确定要退出登录吗？")
                .setPositiveButton("确定", (dialog, which) -> performSignOut())
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 显示注销确认对话框
     */
    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("确认注销")
                .setMessage("注销后将删除您的所有数据，此操作不可恢复。确定要注销吗？")
                .setPositiveButton("确定", (dialog, which) -> performLogout())
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 执行解绑情侣关系操作
     */
    private void performUnbind() {
        CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
        int userIdInt = Integer.parseInt(userId);
        coupleHelper.unbindCouple(userIdInt, new CoupleRelationshipHelper.UnbindCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "解绑成功", Toast.LENGTH_SHORT).show();
                    // 重新加载情侣信息，隐藏相关UI
                    loadCoupleInfo();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "解绑失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 执行退出登录操作
     */
    private void performSignOut() {
        // 使用UserInfoManager清除用户信息
        UserInfoManager.clearUserInfo(this);

        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();

        // 跳转到登录界面
        Intent intent = new Intent(UserSettingsActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
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
                    // 使用UserInfoManager清空用户登录状态
                    UserInfoManager.clearUserInfo(UserSettingsActivity.this);
                    
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
    
    /**
     * 显示修改昵称对话框
     */
    private void showChangeNicknameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("修改昵称");
        
        // 创建输入框
        final EditText input = new EditText(this);
        input.setHint("请输入新昵称");
        builder.setView(input);
        
        builder.setPositiveButton("确定", (dialog, which) -> {
            String newNickname = input.getText().toString().trim();
            if (newNickname.isEmpty()) {
                Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newNickname.length() > 20) {
                Toast.makeText(this, "昵称长度不能超过20个字符", Toast.LENGTH_SHORT).show();
                return;
            }
            updateNickname(newNickname);
        });
        
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    /**
     * 更新用户昵称
     */
    private void updateNickname(String newNickname) {
        if (username == null || username.isEmpty()) {
            Toast.makeText(this, "用户信息异常，无法修改昵称", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(this, "正在更新昵称...", Toast.LENGTH_SHORT).show();
        
        MySQLDatabaseHelper.updateUserNickname(username, newNickname, new MySQLDatabaseHelper.DatabaseCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "昵称修改成功", Toast.LENGTH_SHORT).show();
                    // 刷新用户信息显示
                    updateUserInfo();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "昵称修改失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}