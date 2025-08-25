package com.example.couplecredit.activity;

import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;
import com.example.couplecredit.function.CustomToast;

/**
 * Toast演示页面
 * 展示不同类型的自定义Toast效果
 */
public class ToastDemoActivity extends AppCompatActivity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toast_demo);
        
        // 设置标题栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("自定义Toast演示");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        // 初始化按钮
        initButtons();
    }
    
    private void initButtons() {
        Button btnNormal = findViewById(R.id.btn_normal_toast);
        Button btnSuccess = findViewById(R.id.btn_success_toast);
        Button btnError = findViewById(R.id.btn_error_toast);
        Button btnWarning = findViewById(R.id.btn_warning_toast);
        Button btnInfo = findViewById(R.id.btn_info_toast);
        
        // 普通Toast
        btnNormal.setOnClickListener(v -> 
            CustomToast.show(this, "这是普通的自定义Toast消息")
        );
        
        // 成功Toast
        btnSuccess.setOnClickListener(v -> 
            CustomToast.showSuccess(this, "操作成功！数据已保存")
        );
        
        // 错误Toast
        btnError.setOnClickListener(v -> 
            CustomToast.showError(this, "操作失败！请检查网络连接")
        );
        
        // 警告Toast
        btnWarning.setOnClickListener(v -> 
            CustomToast.showWarning(this, "警告：请先完成必填项")
        );
        
        // 信息Toast
        btnInfo.setOnClickListener(v -> 
            CustomToast.showInfo(this, "提示：这是一条重要信息")
        );
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}