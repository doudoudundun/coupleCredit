package com.example.couplecredit.activity;

import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

/**
 * 编辑账号。复用 activity_account_form 布局：加载详情回填 → 提交更新。
 */
public class EditAccountActivity extends BaseAccountFormActivity {

    private int accountId;
    private int currentUserId;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_form);

        accountId = getIntent().getIntExtra("accountId", -1);
        currentUserId = UserInfoManager.getCurrentUserId(this);

        if (accountId == -1 || currentUserId == -1) {
            Toast.makeText(this, "参数错误", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupForm("编辑账号");
        findViewById(R.id.btn_save).setOnClickListener(v -> submit());
        loadDetail();
    }

    private void loadDetail() {
        AuthApiClient.getPasswordAccountById(this, accountId, currentUserId,
                new AuthApiClient.PasswordAccountDetailCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.PasswordAccountItemData account) {
                        runOnUiThread(() -> populate(account));
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(EditAccountActivity.this, "加载失败: " + message, Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }
                });
    }

    private void populate(AuthApiModels.PasswordAccountItemData a) {
        etPlatformName.setText(a.platformName);
        etAccount.setText(a.accountIdentifier);
        etPassword.setText(orEmpty(a.password));
        etPhone.setText(orEmpty(a.phone));
        etEmail.setText(orEmpty(a.email));
        etWebsite.setText(orEmpty(a.websiteUrl));
        etSecurityQuestion.setText(orEmpty(a.securityQuestion));
        etSecurityAnswer.setText(orEmpty(a.securityAnswer));
        etNote.setText(orEmpty(a.note));
        setCategorySelection(a.category);
    }

    private void submit() {
        String platformName = etPlatformName.getText().toString().trim();
        String account = etAccount.getText().toString().trim();

        if (platformName.isEmpty() || account.isEmpty()) {
            Toast.makeText(this, "平台名和账号不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        View btnSave = findViewById(R.id.btn_save);
        btnSave.setEnabled(false);
        btnSave.setAlpha(0.5f);

        String category = (String) spinnerCategory.getSelectedItem();
        AuthApiModels.UpdatePasswordAccountRequest request = new AuthApiModels.UpdatePasswordAccountRequest(
                currentUserId,
                platformName,
                account,
                opt(etPhone), opt(etEmail), opt(etWebsite),
                opt(etPassword), opt(etSecurityQuestion), opt(etSecurityAnswer),
                category, opt(etNote));

        AuthApiClient.updatePasswordAccount(this, accountId, request, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(EditAccountActivity.this, "更新成功", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(EditAccountActivity.this, "更新失败: " + message, Toast.LENGTH_SHORT).show();
                    btnSave.setEnabled(true);
                    btnSave.setAlpha(1f);
                });
            }
        });
    }

    private String opt(EditText et) {
        String s = et.getText().toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
