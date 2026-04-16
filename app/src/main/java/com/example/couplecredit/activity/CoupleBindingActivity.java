package com.example.couplecredit.activity;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.database.CoupleRelationshipHelper;
import com.example.couplecredit.R;
import com.example.couplecredit.database.MySQLDatabaseHelper;

public class CoupleBindingActivity extends AppCompatActivity {
    private Button btnGenerateInvite, btnInputInvite;
    private CoupleRelationshipHelper coupleHelper;
    private SharedPreferences sharedPreferences;
    private int currentUserId;
    private String currentUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_couple_binding);

        initViews();
        initData();
        setupListeners();
    }

    private void initViews() {
        btnGenerateInvite = findViewById(R.id.btn_generate_invite);
        btnInputInvite = findViewById(R.id.btn_input_invite);
    }

    private void initData() {
        coupleHelper = new CoupleRelationshipHelper();
        
        // 从Intent获取用户信息
        Intent intent = getIntent();
        String intentUserId = intent.getStringExtra("userId");
        currentUsername = intent.getStringExtra("username");
        
        // 如果Intent中没有username，则从SharedPreferences获取
        if (currentUsername == null || currentUsername.isEmpty()) {
            sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
            currentUsername = sharedPreferences.getString("username", "");
        }

        if (currentUsername == null || currentUsername.isEmpty()) {
            Toast.makeText(this, "用户信息异常，请重新登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // 通过username查询真正的用户ID
        MySQLDatabaseHelper.getUserIdByUsername(currentUsername, new MySQLDatabaseHelper.UserIdCallback() {
            @Override
            public void onSuccess(int userId) {
                currentUserId = userId;
                // 用户ID获取成功，可以继续后续操作
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(CoupleBindingActivity.this, "获取用户信息失败: " + error, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void setupListeners() {
        btnGenerateInvite.setOnClickListener(v -> generateInviteCode());
        btnInputInvite.setOnClickListener(v -> showInputInviteDialog());
    }

    private void generateInviteCode() {
        btnGenerateInvite.setEnabled(false);
        btnGenerateInvite.setText("生成中...");

        coupleHelper.generateInviteCode(currentUserId, new CoupleRelationshipHelper.InviteCodeCallback() {
            @Override
            public void onInviteCodeGenerated(String inviteCode) {
                runOnUiThread(() -> {
                    btnGenerateInvite.setEnabled(true);
                    btnGenerateInvite.setText("发起邀请");
                    showInviteCodeDialog(inviteCode);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnGenerateInvite.setEnabled(true);
                    btnGenerateInvite.setText("发起邀请");
                    Toast.makeText(CoupleBindingActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showInviteCodeDialog(String inviteCode) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_invite_code);
        dialog.setCancelable(false);

        TextView tvInviteCode = dialog.findViewById(R.id.tv_invite_code);
        LinearLayout llCopyButton = dialog.findViewById(R.id.ll_copy_button);
        ImageView ivClose = dialog.findViewById(R.id.iv_close);

        tvInviteCode.setText(inviteCode);

        // 复制邀请码
        llCopyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("邀请码", inviteCode);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "邀请码已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

        // 关闭弹窗
        ivClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showInputInviteDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_input_invite, null);
        builder.setView(dialogView);

        EditText etInviteCode = dialogView.findViewById(R.id.et_invite_code);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);

        AlertDialog dialog = builder.create();

        btnConfirm.setOnClickListener(v -> {
            String inviteCode = etInviteCode.getText().toString().trim().toUpperCase();
            if (inviteCode.isEmpty()) {
                Toast.makeText(this, "请输入邀请码", Toast.LENGTH_SHORT).show();
                return;
            }
            if (inviteCode.length() != 6) {
                Toast.makeText(this, "邀请码格式错误", Toast.LENGTH_SHORT).show();
                return;
            }
            
            btnConfirm.setEnabled(false);
            btnConfirm.setText("验证中...");
            
            searchAndBindCouple(inviteCode, dialog, btnConfirm);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void searchAndBindCouple(String inviteCode, AlertDialog dialog, Button btnConfirm) {
        coupleHelper.findUserByInviteCode(inviteCode, new CoupleRelationshipHelper.UserSearchCallback() {
            @Override
            public void onUserFound(int userId, String username) {
                runOnUiThread(() -> {
                    // 显示确认绑定对话框
                    showBindConfirmDialog(userId, username, dialog, btnConfirm);
                });
            }

            @Override
            public void onUserNotFound() {
                runOnUiThread(() -> {
                    btnConfirm.setEnabled(true);
                    btnConfirm.setText("确认");
                    Toast.makeText(CoupleBindingActivity.this, "邀请码无效或已过期", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnConfirm.setEnabled(true);
                    btnConfirm.setText("确认");
                    Toast.makeText(CoupleBindingActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showBindConfirmDialog(int inviterId, String inviterUsername, AlertDialog inputDialog, Button btnConfirm) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认绑定");
        builder.setMessage("确定要与 " + inviterUsername + " 建立情侣关系吗？");
        
        builder.setPositiveButton("确定", (confirmDialog, which) -> {
            // 创建情侣关系
            coupleHelper.createCoupleRelationship(inviterId, currentUserId, new CoupleRelationshipHelper.CoupleCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(CoupleBindingActivity.this, message, Toast.LENGTH_LONG).show();
                        inputDialog.dismiss();
                        finish(); // 返回上一页面
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        btnConfirm.setEnabled(true);
                        btnConfirm.setText("确认");
                        Toast.makeText(CoupleBindingActivity.this, error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
        
        builder.setNegativeButton("取消", (confirmDialog, which) -> {
            btnConfirm.setEnabled(true);
            btnConfirm.setText("确认");
            confirmDialog.dismiss();
        });
        
        builder.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}