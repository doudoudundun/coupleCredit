package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;

/**
 * v0.2 共同目标创建/编辑（空壳）：目标名/目标金额/目标日期表单骨架。
 * 暂不接入任何页面入口。
 */
public class GoalEditActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_goal_edit);

        Button btnPickDate = findViewById(R.id.btn_pick_target_date);
        Button btnSaveGoal = findViewById(R.id.btn_save_goal);

        btnPickDate.setOnClickListener(v -> showWip());
        btnSaveGoal.setOnClickListener(v -> showWip());
    }

    private void showWip() {
        Toast.makeText(this, "v0.2 功能开发中，敬请期待", Toast.LENGTH_SHORT).show();
    }
}
