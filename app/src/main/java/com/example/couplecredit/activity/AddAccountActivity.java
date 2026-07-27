package com.example.couplecredit.activity;

import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

/**
 * 新增账号。复用 activity_account_form 布局与 BaseAccountFormActivity 的公共表单逻辑。
 */
public class AddAccountActivity extends BaseAccountFormActivity {

    private int currentUserId;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_form);

        currentUserId = UserInfoManager.getCurrentUserId(this);
        if (currentUserId == -1) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupForm("添加账号");

        findViewById(R.id.btn_save).setOnClickListener(v -> submit());
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
        AuthApiModels.CreatePasswordAccountRequest request = new AuthApiModels.CreatePasswordAccountRequest(
                currentUserId,
                platformName,
                account,
                opt(etPhone), opt(etEmail), opt(etWebsite),
                opt(etPassword), opt(etSecurityQuestion), opt(etSecurityAnswer),
                category, opt(etNote));

        AuthApiClient.createPasswordAccount(this, request, new AuthApiClient.PasswordAccountMutationCallback() {
            @Override
            public void onSuccess(AuthApiModels.PasswordAccountMutationResponse response) {
                runOnUiThread(() -> {
                    Toast.makeText(AddAccountActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(AddAccountActivity.this, "保存失败: " + message, Toast.LENGTH_SHORT).show();
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
}
