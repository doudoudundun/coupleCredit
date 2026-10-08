package com.example.couplecredit.activity;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.utils.UserInfoManager;

public class CoupleBindingActivity extends AppCompatActivity {
    private Button btnGenerateInvite, btnInputInvite;
    private int currentUserId;

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
        currentUserId = UserInfoManager.getCurrentUserId(this);
        if (currentUserId <= 0) {
            Toast.makeText(this, "用户信息异常，请重新登录", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupListeners() {
        btnGenerateInvite.setOnClickListener(v -> generateInviteCode());
        btnInputInvite.setOnClickListener(v -> showInputInviteDialog());
    }

    private void generateInviteCode() {
        btnGenerateInvite.setEnabled(false);
        btnGenerateInvite.setText("生成中...");

        AuthApiClient.generateInviteCode(this, currentUserId, new AuthApiClient.InviteCodeCallback() {
            @Override
            public void onSuccess(String inviteCode) {
                runOnUiThread(() -> {
                    btnGenerateInvite.setEnabled(true);
                    btnGenerateInvite.setText("发起邀请");
                    showInviteCodeDialog(inviteCode);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btnGenerateInvite.setEnabled(true);
                    btnGenerateInvite.setText("发起邀请");
                    Toast.makeText(CoupleBindingActivity.this, message, Toast.LENGTH_SHORT).show();
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
        AuthApiClient.searchByInviteCode(this, inviteCode, new AuthApiClient.UserSearchCallback() {
            @Override
            public void onFound(int userId, String username, String nickname) {
                runOnUiThread(() -> {
                    String displayName = (nickname != null && !nickname.isEmpty()) ? nickname : username;
                    showBindConfirmDialog(inviteCode, displayName, dialog, btnConfirm);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btnConfirm.setEnabled(true);
                    btnConfirm.setText("确认");
                    Toast.makeText(CoupleBindingActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showBindConfirmDialog(String inviteCode, String inviterUsername, AlertDialog inputDialog, Button btnConfirm) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认绑定");
        builder.setMessage("确定要与 " + inviterUsername + " 建立情侣关系吗？");
        
        builder.setPositiveButton("确定", (confirmDialog, which) -> {
            // 创建情侣关系
            AuthApiClient.bindCouple(CoupleBindingActivity.this, inviteCode, new AuthApiClient.SimpleIdCallback() {
                @Override
                public void onSuccess(int relationshipId) {
                    runOnUiThread(() -> {
                        Toast.makeText(CoupleBindingActivity.this, "绑定成功", Toast.LENGTH_LONG).show();
                        inputDialog.dismiss();
                        finish(); // 返回上一页面
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        btnConfirm.setEnabled(true);
                        btnConfirm.setText("确认");
                        Toast.makeText(CoupleBindingActivity.this, message, Toast.LENGTH_SHORT).show();
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
