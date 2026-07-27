package com.example.couplecredit.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.PlatformIconHelper;
import com.example.couplecredit.utils.UserInfoManager;

import android.graphics.drawable.GradientDrawable;

public class AccountDetailActivity extends AppCompatActivity {

    private int accountId;
    private int currentUserId;
    private AuthApiModels.PasswordAccountItemData currentAccount;

    private boolean passwordVisible = false;
    private boolean answerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_detail);

        accountId = getIntent().getIntExtra("accountId", -1);
        currentUserId = UserInfoManager.getCurrentUserId(this);

        if (accountId == -1 || currentUserId == -1) {
            Toast.makeText(this, "参数错误", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        loadAccountDetail();
    }

    private void initViews() {
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        findViewById(R.id.iv_edit).setOnClickListener(v -> {
            Intent intent = new Intent(this, EditAccountActivity.class);
            intent.putExtra("accountId", accountId);
            startActivity(intent);
        });
        findViewById(R.id.iv_delete).setOnClickListener(v -> confirmDelete());

        findViewById(R.id.iv_toggle_password).setOnClickListener(v -> togglePassword());
        findViewById(R.id.iv_toggle_answer).setOnClickListener(v -> toggleAnswer());
        findViewById(R.id.iv_copy_password).setOnClickListener(v ->
                copyToClipboard("密码", currentAccount != null ? currentAccount.password : null));
    }

    private void loadAccountDetail() {
        AuthApiClient.getPasswordAccountById(this, accountId, currentUserId,
                new AuthApiClient.PasswordAccountDetailCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.PasswordAccountItemData account) {
                        runOnUiThread(() -> {
                            currentAccount = account;
                            displayDetails();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (currentAccount == null) {
                                Toast.makeText(AccountDetailActivity.this, "加载失败: " + message, Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        });
                    }
                });
    }

    private void displayDetails() {
        if (currentAccount == null) return;

        // 平台图标
        ImageView ivIcon = findViewById(R.id.iv_platform_icon);
        TextView tvInitial = findViewById(R.id.tv_platform_initial);
        int iconRes = PlatformIconHelper.getIconResForPlatform(this, currentAccount.platformName);
        if (iconRes != 0) {
            ivIcon.setVisibility(View.VISIBLE);
            tvInitial.setVisibility(View.GONE);
            ivIcon.setImageResource(iconRes);
        } else {
            ivIcon.setVisibility(View.GONE);
            tvInitial.setVisibility(View.VISIBLE);
            tvInitial.setText(PlatformIconHelper.getInitial(currentAccount.platformName));
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(PlatformIconHelper.getColorForPlatform(currentAccount.platformName));
            tvInitial.setBackground(bg);
        }

        ((TextView) findViewById(R.id.tv_platform_name)).setText(currentAccount.platformName);
        String category = currentAccount.category != null && !currentAccount.category.isEmpty()
                ? currentAccount.category : "其他";
        ((TextView) findViewById(R.id.tv_category)).setText(category);

        // 标准字段行
        setField(R.id.row_account, "账号", currentAccount.accountIdentifier);
        setField(R.id.row_phone, "手机号", currentAccount.phone);
        setField(R.id.row_email, "邮箱", currentAccount.email);
        setField(R.id.row_security_question, "安全问题", currentAccount.securityQuestion);

        // 密码（默认掩码）
        TextView tvPassword = findViewById(R.id.tv_password);
        tvPassword.setText(maskIfHidden(currentAccount.password, passwordVisible));

        // 安全答案（默认掩码）
        TextView tvAnswer = findViewById(R.id.tv_security_answer);
        tvAnswer.setText(maskIfHidden(currentAccount.securityAnswer, answerVisible));

        // 网站链接
        TextView tvWebsite = findViewById(R.id.tv_website);
        if (currentAccount.websiteUrl != null && !currentAccount.websiteUrl.isEmpty()) {
            tvWebsite.setText(currentAccount.websiteUrl);
            tvWebsite.setOnClickListener(v -> openUrl(currentAccount.websiteUrl));
        } else {
            tvWebsite.setText("未设置");
            tvWebsite.setOnClickListener(null);
        }

        // 备注
        TextView tvNote = findViewById(R.id.tv_note);
        tvNote.setText(currentAccount.note != null && !currentAccount.note.isEmpty() ? currentAccount.note : "未设置");

        // 时间
        ((TextView) findViewById(R.id.tv_created)).setText("创建：" + (currentAccount.createdAt != null ? currentAccount.createdAt : "-"));
        ((TextView) findViewById(R.id.tv_updated)).setText("更新：" + (currentAccount.updatedAt != null ? currentAccount.updatedAt : "-"));
    }

    /** 为 include 的字段行设置标签和值 */
    private void setField(int rowId, String label, String value) {
        View row = findViewById(rowId);
        if (row == null) return;
        ((TextView) row.findViewById(R.id.tv_field_label)).setText(label);
        String v = (value != null && !value.isEmpty()) ? value : "未设置";
        ((TextView) row.findViewById(R.id.tv_field_value)).setText(v);
    }

    private String maskIfHidden(String value, boolean visible) {
        if (value == null || value.isEmpty()) return "未设置";
        if (visible) return value;
        return "••••••••";
    }

    private void togglePassword() {
        passwordVisible = !passwordVisible;
        ((ImageView) findViewById(R.id.iv_toggle_password))
                .setImageResource(passwordVisible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
        if (currentAccount != null) {
            ((TextView) findViewById(R.id.tv_password))
                    .setText(maskIfHidden(currentAccount.password, passwordVisible));
        }
    }

    private void toggleAnswer() {
        answerVisible = !answerVisible;
        ((ImageView) findViewById(R.id.iv_toggle_answer))
                .setImageResource(answerVisible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
        if (currentAccount != null) {
            ((TextView) findViewById(R.id.tv_security_answer))
                    .setText(maskIfHidden(currentAccount.securityAnswer, answerVisible));
        }
    }

    private void copyToClipboard(String label, String value) {
        if (value == null || value.isEmpty()) {
            Toast.makeText(this, "无内容可复制", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, "已复制" + label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        String target = url;
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete() {
        View view = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        ((TextView) view.findViewById(R.id.tv_confirm_title)).setText("删除账号");
        ((TextView) view.findViewById(R.id.tv_confirm_message)).setText("确定要删除这个账号记录吗？");

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.CustomDialogStyle)
                .setView(view)
                .create();
        view.findViewById(R.id.btn_confirm_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_confirm_delete).setOnClickListener(v -> {
            dialog.dismiss();
            deleteAccount();
        });
        dialog.show();
    }

    private void deleteAccount() {
        AuthApiClient.deletePasswordAccount(this, accountId, currentUserId, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(AccountDetailActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(AccountDetailActivity.this, "删除失败: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑页返回时刷新
        if (currentAccount != null) {
            loadAccountDetail();
        }
    }
}
