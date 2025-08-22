package com.example.couplecredit;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.function.MySQLDatabaseHelper;

/**
 * 数据库连接测试Activity
 */
public class TestDatabaseActivity extends AppCompatActivity {
    
    private TextView tvResult;
    private Button btnTest;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_database);
        
        tvResult = findViewById(R.id.tv_result);
        btnTest = findViewById(R.id.btn_test);
        
        btnTest.setOnClickListener(v -> testDatabaseConnection());
    }
    
    private void testDatabaseConnection() {
        tvResult.setText("正在测试数据库连接...");
        
        // 测试插入一个用户
        String testUsername = "test_" + System.currentTimeMillis();
        String testEmail = testUsername + "@test.com";
        String testPassword = "test123";
        
        MySQLDatabaseHelper.insertUser(testUsername, testEmail, testPassword, new MySQLDatabaseHelper.DatabaseCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    tvResult.setText("数据库连接成功！\n" + message);
                    Toast.makeText(TestDatabaseActivity.this, "数据库连接正常", Toast.LENGTH_SHORT).show();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvResult.setText("数据库连接失败：\n" + error);
                    Toast.makeText(TestDatabaseActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}