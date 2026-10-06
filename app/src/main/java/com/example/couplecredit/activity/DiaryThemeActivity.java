package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;

/**
 * v0.2 空间背景设置（空壳）：模板三选/自定义上传/柔化滑杆骨架。
 * 保存走 SpaceApiClient.saveDiaryTheme（乐观锁），后续迭代接线。
 * 暂不接入任何页面入口。
 */
public class DiaryThemeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diary_theme);

        Button btnUploadCustom = findViewById(R.id.btn_upload_custom_bg);
        Button btnPreview = findViewById(R.id.btn_preview_theme);
        Button btnSaveTheme = findViewById(R.id.btn_save_theme);

        btnUploadCustom.setOnClickListener(v -> showWip());
        btnPreview.setOnClickListener(v -> showWip());
        btnSaveTheme.setOnClickListener(v -> showWip());
    }

    private void showWip() {
        Toast.makeText(this, "v0.2 功能开发中，敬请期待", Toast.LENGTH_SHORT).show();
    }
}
