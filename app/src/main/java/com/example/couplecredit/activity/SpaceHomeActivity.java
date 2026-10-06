package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;

/**
 * v0.2 共同空间主页（空壳）：共同目标卡片 + 共同小记时间线的页面骨架。
 * 功能待后续迭代（接口层见 SpaceApiClient）；暂不接入任何页面入口。
 */
public class SpaceHomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_space_home);

        Button btnGoalAction = findViewById(R.id.btn_goal_action);
        Button btnWriteDiary = findViewById(R.id.btn_write_diary);
        Button btnSpaceTheme = findViewById(R.id.btn_space_theme);

        btnGoalAction.setOnClickListener(v -> showWip());
        btnWriteDiary.setOnClickListener(v -> showWip());
        btnSpaceTheme.setOnClickListener(v -> showWip());
    }

    private void showWip() {
        Toast.makeText(this, "v0.2 功能开发中，敬请期待", Toast.LENGTH_SHORT).show();
    }
}
