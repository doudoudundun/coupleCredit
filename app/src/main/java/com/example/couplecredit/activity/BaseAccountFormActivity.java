package com.example.couplecredit.activity;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.PlatformIconHelper;

import java.security.SecureRandom;

/**
 * 新增/编辑账号的公共表单逻辑基类。
 * 子类需：setContentView(R.layout.activity_account_form) 后调用 super.setupForm()，
 * 并实现 onSave() 处理提交、可选重写 getPlatformSuggestions()。
 */
abstract class BaseAccountFormActivity extends AppCompatActivity {

    AutoCompleteTextView etPlatformName;
    EditText etAccount, etPassword, etPhone, etEmail, etWebsite,
            etSecurityQuestion, etSecurityAnswer, etNote;
    Spinner spinnerCategory;
    ImageView ivPreview;
    TextView tvInitial;

    private boolean passwordVisible = false;

    // 预设分类
    static final String[] CATEGORIES = {"社交", "购物", "金融", "工具", "游戏", "娱乐", "其他"};

    // 常见平台（用于 AutoCompleteTextView 自动补全）
    static final String[] PLATFORM_SUGGESTIONS = {
            "微信", "支付宝", "QQ", "淘宝", "天猫", "京东", "拼多多", "美团", "饿了么",
            "抖音", "快手", "小红书", "微博", "哔哩哔哩", "百度", "网易", "知乎", "滴滴",
            "中国移动", "中国联通", "中国电信", "Apple", "Google", "Microsoft", "GitHub"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    void setupForm(String title) {
        ((TextView) findViewById(R.id.tv_title)).setText(title);
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        etPlatformName = findViewById(R.id.et_platform_name);
        etAccount = findViewById(R.id.et_account);
        etPassword = findViewById(R.id.et_password);
        etPhone = findViewById(R.id.et_phone);
        etEmail = findViewById(R.id.et_email);
        etWebsite = findViewById(R.id.et_website);
        etSecurityQuestion = findViewById(R.id.et_security_question);
        etSecurityAnswer = findViewById(R.id.et_security_answer);
        etNote = findViewById(R.id.et_note);
        spinnerCategory = findViewById(R.id.spinner_category);
        ivPreview = findViewById(R.id.iv_platform_preview);
        tvInitial = findViewById(R.id.tv_platform_initial);

        // 分类 spinner
        spinnerCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, CATEGORIES));
        ((ArrayAdapter<?>) spinnerCategory.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 平台名自动补全
        etPlatformName.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, PLATFORM_SUGGESTIONS));

        // 平台名变化时更新图标预览
        etPlatformName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePlatformPreview(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 密码显示/隐藏
        findViewById(R.id.iv_toggle_password).setOnClickListener(v -> togglePasswordVisibility());
        // 随机生成密码
        findViewById(R.id.iv_generate_password).setOnClickListener(v -> generateRandomPassword());
    }

    private void updatePlatformPreview(String name) {
        int iconRes = PlatformIconHelper.getIconResForPlatform(this, name);
        if (iconRes != 0) {
            ivPreview.setVisibility(View.VISIBLE);
            tvInitial.setVisibility(View.GONE);
            ivPreview.setImageResource(iconRes);
        } else {
            ivPreview.setVisibility(View.GONE);
            tvInitial.setVisibility(View.VISIBLE);
            tvInitial.setText(PlatformIconHelper.getInitial(name));
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(PlatformIconHelper.getColorForPlatform(name));
            tvInitial.setBackground(bg);
        }
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        int sel = etPassword.getSelectionEnd();
        if (passwordVisible) {
            etPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            ((ImageView) findViewById(R.id.iv_toggle_password)).setImageResource(R.drawable.ic_visibility);
        } else {
            etPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            ((ImageView) findViewById(R.id.iv_toggle_password)).setImageResource(R.drawable.ic_visibility_off);
        }
        // 恢复字体与光标
        etPassword.setTextSize(14f);
        if (sel >= 0 && sel <= etPassword.length()) etPassword.setSelection(sel);
    }

    /** 生成 16 位强随机密码（大小写字母+数字+符号） */
    private void generateRandomPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String symbols = "!@#$%^&*-_=+?";
        String all = upper + lower + digits + symbols;
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        // 保证每类至少一个
        sb.append(upper.charAt(rng.nextInt(upper.length())));
        sb.append(lower.charAt(rng.nextInt(lower.length())));
        sb.append(digits.charAt(rng.nextInt(digits.length())));
        sb.append(symbols.charAt(rng.nextInt(symbols.length())));
        for (int i = 0; i < 12; i++) {
            sb.append(all.charAt(rng.nextInt(all.length())));
        }
        // 打乱顺序
        char[] arr = sb.toString().toCharArray();
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            char tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
        String pwd = new String(arr);
        etPassword.setText(pwd);
        etPassword.setSelection(pwd.length());
    }

    /** 把当前选中分类的下标置为指定值（用于编辑回填） */
    void setCategorySelection(String category) {
        if (category == null) {
            spinnerCategory.setSelection(CATEGORIES.length - 1); // 其他
            return;
        }
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(category)) {
                spinnerCategory.setSelection(i);
                return;
            }
        }
        spinnerCategory.setSelection(CATEGORIES.length - 1);
    }
}
