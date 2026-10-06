package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;

/**
 * v0.2 写小记（空壳）：经历日期/此刻心情/正文/照片表单骨架。
 * 暂不接入任何页面入口。
 */
public class DiaryEditActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diary_edit);

        Button btnPickDate = findViewById(R.id.btn_pick_occurred_date);
        Button btnPickMood = findViewById(R.id.btn_pick_mood);
        Button btnAddPhoto = findViewById(R.id.btn_add_photo);
        Button btnPublish = findViewById(R.id.btn_publish_diary);

        btnPickDate.setOnClickListener(v -> showWip());
        btnPickMood.setOnClickListener(v -> showWip());
        btnAddPhoto.setOnClickListener(v -> showWip());
        btnPublish.setOnClickListener(v -> showWip());
    }

    private void showWip() {
        Toast.makeText(this, "v0.2 功能开发中，敬请期待", Toast.LENGTH_SHORT).show();
    }
}
