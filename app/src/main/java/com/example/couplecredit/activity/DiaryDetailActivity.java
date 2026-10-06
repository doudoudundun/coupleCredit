package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;

/**
 * v0.2 小记详情与评论（空壳）：正文/照片/评论列表/评论输入骨架。
 * 暂不接入任何页面入口。
 */
public class DiaryDetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diary_detail);

        Button btnSendComment = findViewById(R.id.btn_send_comment);
        btnSendComment.setOnClickListener(v ->
                Toast.makeText(this, "v0.2 功能开发中，敬请期待", Toast.LENGTH_SHORT).show());
    }
}
