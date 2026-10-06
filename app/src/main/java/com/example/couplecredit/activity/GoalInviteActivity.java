package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;

/**
 * v0.2 情境邀请落地页（空壳）：展示邀请预览（邀请人/目标），明示同意后接受。
 * 预览与接受走 SpaceApiClient.previewInvitation / acceptInvitation，后续迭代接线。
 * 暂不接入任何页面入口。
 */
public class GoalInviteActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_goal_invite);

        Button btnAccept = findViewById(R.id.btn_accept_invite);
        Button btnDecline = findViewById(R.id.btn_decline_invite);

        btnAccept.setOnClickListener(v -> showWip());
        btnDecline.setOnClickListener(v -> finish());
    }

    private void showWip() {
        Toast.makeText(this, "v0.2 功能开发中，敬请期待", Toast.LENGTH_SHORT).show();
    }
}
