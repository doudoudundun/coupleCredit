package com.example.couplecredit.activity;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.BillBean;
import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BillAdapter;
import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.fragment.ReportFragment;
import com.example.couplecredit.fragment.HeadFragment;
import com.example.couplecredit.fragment.ClassicModelFragment;
import com.example.couplecredit.function.Utils;
import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.utils.CategoryIconMapper;
import com.transsion.widgetslib.dialog.PromptDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;

public class CategoriesBillViewActivity extends AppCompatActivity {
    private RecyclerView rvBillList;
    private BillAdapter billAdapter;
    private List<Object> displayItems; // 混合数据：String(日期) 和 BillBean
    private List<BillBean> billItems;
    private PromptDialog mDialog;
    private Fragment classmodelfragment;
    private String categoryName;
    private int filterYear;
    private int filterMonth;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_categories_bill_view);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        // 获取Intent传递的类别名称和月份信息
        categoryName = getIntent().getStringExtra("categoryName");
        if (categoryName == null) {
            categoryName = "全部"; // 默认值
        }
        
        // 获取年月筛选参数
        filterYear = getIntent().getIntExtra("year", 0);
        filterMonth = getIntent().getIntExtra("month", 0);
        
        // 添加调试日志
        Log.d("CategoriesBillViewActivity", "接收到参数 - 分类: " + categoryName + ", 年份: " + filterYear + ", 月份: " + filterMonth);
        
        rvBillList = findViewById(R.id.rv_catebill_list);
        // 初始化RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rvBillList.setLayoutManager(layoutManager);
        // classmodelfragment = MainActivity.getHeadFragment();

        // 初始化数据列表
        displayItems = new ArrayList<>();
        // 设置适配器
        billAdapter = new BillAdapter(this, displayItems, new BillAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position, BillBean bill) {
                processDialog(bill);
            }
        });
        rvBillList.setAdapter(billAdapter);
        getCategoryBill();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 销毁Activity，确保下次重新创建
        finish();
    }
    
    private void getCategoryBill() {
        // 获取用户信息
        UserInfoManager.getCurrentUserInfo(this, new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                // 查询账单数据
                BillDatabaseHelper billHelper = new BillDatabaseHelper(CategoriesBillViewActivity.this);
                
                // 构建筛选条件（类别 + 月份）
                String selection = null;
                String[] selectionArgs = null;
                
                // 构建月份筛选条件
                if (filterYear > 0 && filterMonth > 0) {
                    String monthPattern = String.format("%04d-%02d-%%", filterYear, filterMonth);
                    if (!"全部".equals(categoryName)) {
                        selection = "type = ? AND date LIKE ?";
                        selectionArgs = new String[]{categoryName, monthPattern};
                    } else {
                        selection = "date LIKE ?";
                        selectionArgs = new String[]{monthPattern};
                    }
                } else {
                    // 如果没有月份筛选，只按类别筛选
                    if (!"全部".equals(categoryName)) {
                        selection = "type = ?";
                        selectionArgs = new String[]{categoryName};
                    }
                }
                
                billHelper.queryBillsWithUserFilter(userId, relationshipId, selection, selectionArgs, new BillDatabaseHelper.QueryCallback() {
                    @Override
                    public void onSuccess(List<Map<String, Object>> results) {
                        runOnUiThread(() -> {
                            processQueryResults(results);
                        });
                    }
                    
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            Log.e("CategoriesBillViewActivity", "查询账单失败: " + error);
                            // 显示空列表
                            displayItems.clear();
                            billAdapter.notifyDataSetChanged();
                        });
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Log.e("CategoriesBillViewActivity", "获取用户信息失败: " + error);
                    // 显示空列表
                    displayItems.clear();
                    billAdapter.notifyDataSetChanged();
                });
            }
        });
    }
    
    private void processQueryResults(List<Map<String, Object>> results) {
        displayItems.clear();
        billItems = new ArrayList<>();
        
        // 按日期分组
        Map<String, List<BillBean>> dateGroups = new HashMap<>();
        
        for (Map<String, Object> result : results) {
            // 解析日期字符串为年月日
            String dateStr = (String) result.get("date");
            String[] dateParts = dateStr.split("-");
            int year = Integer.parseInt(dateParts[0]);
            int month = Integer.parseInt(dateParts[1]);
            int day = Integer.parseInt(dateParts[2]);
            
            String categoryName = (String) result.get("type");
            
            BillBean bill = new BillBean(
                ((Long) result.get("_id")).longValue(),
                (Double) result.get("amount"),
                year,
                month,
                day,
                (Integer) result.get("owner"),
                (Integer) result.get("userId"),
                categoryName,
                "", // categoryDesc
                getIconForCategory(categoryName),
                (Integer) result.get("income_type"),
                (String) result.get("time"),
                (String) result.get("title"),
                (Integer) result.get("is_help")
            );
            
            billItems.add(bill);
            
            // 按日期分组
            String date = dateStr;
            if (!dateGroups.containsKey(date)) {
                dateGroups.put(date, new ArrayList<>());
            }
            dateGroups.get(date).add(bill);
        }
        
        // 按日期排序并添加到显示列表
        List<String> sortedDates = new ArrayList<>(dateGroups.keySet());
        sortedDates.sort((d1, d2) -> d2.compareTo(d1)); // 降序排列
        
        for (String date : sortedDates) {
            List<BillBean> billsForDate = dateGroups.get(date);
            // 按时间排序
            billsForDate.sort((b1, b2) -> b2.getTime().compareTo(b1.getTime()));
            
            // 创建日期组Map
            Map<String, List<BillBean>> dateGroup = new HashMap<>();
            dateGroup.put(date, billsForDate);
            displayItems.add(dateGroup);
        }
        
        billAdapter.notifyDataSetChanged();
    }

    public void processDialog(BillBean bill){
        double fare = bill.getFare();
        String incomeType = bill.getIncomeType() == 1 ? "收入" : "支出";
        String categoryName = bill.getCategoryName();
        int day = bill.getDay();
        int month = bill.getMonth();
        int year = bill.getYear();
        String date = String.format("%04d-%02d-%02d", year, month, day);

        // 创建对话框
        mDialog = new PromptDialog.Builder(this)
                .setTitle("账单详情")
                .setView(R.layout.dialog_layout)
                .show();

        // 获取对话框中的视图组件
        ImageView ivCategoryIcon = mDialog.findViewById(R.id.iv_category_icon);
        TextView tvCategoryName = mDialog.findViewById(R.id.tv_category_name);
        TextView tvDate = mDialog.findViewById(R.id.tv_date);
        TextView tvFare = mDialog.findViewById(R.id.tv_fare);
        TextView tvNoteContent = mDialog.findViewById(R.id.tv_note_content);
        
        EditText etFare = mDialog.findViewById(R.id.et_fare);
        EditText etNoteContent = mDialog.findViewById(R.id.et_note_content);
        
        Button btnDelete = mDialog.findViewById(R.id.btn_delete);
        Button btnEdit = mDialog.findViewById(R.id.btn_edit);
        ImageButton btnConfirm = mDialog.findViewById(R.id.btn_confirm);
        ImageButton btnCancel = mDialog.findViewById(R.id.btn_cancel);
        LinearLayout llNoteCard = mDialog.findViewById(R.id.ll_note_card);

        // 设置数据
        if (ivCategoryIcon != null) {
            int iconResId = getIconForCategory(categoryName);
            ivCategoryIcon.setImageResource(iconResId);
            ivCategoryIcon.setBackground(null); // 移除背景色，显示图标
        }
        if (tvCategoryName != null) tvCategoryName.setText(incomeType + "-" + categoryName);
        if (tvDate != null) tvDate.setText(date);
        if (tvFare != null) tvFare.setText("￥ " + String.format("%.2f", fare));
        
        // 设置EditText的初始值
        if (etFare != null) etFare.setText("￥" + String.format("%.2f", fare));
        
        // 处理备注显示
        String noteTitle = bill.getTitle();
        if (noteTitle != null && !noteTitle.trim().isEmpty() && !noteTitle.equals(categoryName)) {
            // 有备注且备注不等于分类名称时显示备注卡片
            if (llNoteCard != null) llNoteCard.setVisibility(View.VISIBLE);
            if (tvNoteContent != null) tvNoteContent.setText(noteTitle);
            if (etNoteContent != null) etNoteContent.setText(noteTitle);
        } else {
            // 没有备注或备注等于分类名称时隐藏备注卡片
            if (llNoteCard != null) llNoteCard.setVisibility(View.GONE);
        }
        
        if (btnDelete != null) btnDelete.setOnClickListener(v -> {
            showWarningDialog(bill);
        });
        
        if (btnEdit != null) btnEdit.setOnClickListener(v -> {
            Utils.enterEditMode(this, mDialog, tvDate, tvFare, tvNoteContent,
                    etFare, etNoteContent,
                    btnEdit, btnDelete, btnConfirm, btnCancel);
        });
        
        if (btnConfirm != null) btnConfirm.setOnClickListener(v -> {
            // 确认修改并更新数据库
            String currentDate = tvDate.getText().toString();
            String fareText = etFare.getText().toString();
            // 判空逻辑：如果包含货币符号则去掉，否则直接解析
            double currentFare = fareText.startsWith("￥") ?
                    Double.parseDouble(fareText.substring(1)) :
                    Double.parseDouble(fareText);
            String currentNoteContent = etNoteContent.getText().toString();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTime = timeFormat.format(new Date());

            Utils.updateBill(this, bill, currentDate, currentFare, currentNoteContent, currentTime, new Utils.UpdateBillCallback() {
                @Override
                public void onUpdateSuccess(int rowsAffected) {
                    runOnUiThread(() -> {
                        if (rowsAffected > 0) {
                            // 更新成功，刷新数据
                            refreshBillData();
                            // 通知首页刷新数据
                            notifyHomePageRefresh();
                            mDialog.dismiss();
                        }
                    });
                }

                @Override
                public void onUpdateError(String error) {
                    runOnUiThread(() -> {
                        Log.e("CategoriesBillViewActivity", "更新账单失败: " + error);
                    });
                }
            });
            Utils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                    etFare, etNoteContent,
                    btnEdit, btnDelete, btnConfirm, btnCancel);
        });
        
        if (btnCancel != null) btnCancel.setOnClickListener(v -> {
            if (etFare != null) etFare.setText("￥" + String.format("%.2f", fare));
            if (etNoteContent != null) etNoteContent.setText(noteTitle != null ? noteTitle : "");

            // 更新TextView显示原始数据
            if (tvFare != null) tvFare.setText("￥ " + String.format("%.2f", fare));
            if (tvNoteContent != null) tvNoteContent.setText(noteTitle != null ? noteTitle : "");

            Utils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                    etFare, etNoteContent,
                    btnEdit, btnDelete, btnConfirm, btnCancel);
        });
    }
    
    private int getIconForCategory(String category) {
        // 处理一些历史数据的分类名称映射
        String mappedCategory = category;
        switch (category) {
            // 餐品已在CategoryIconMapper中直接映射，不需要转换
            case "社交":
                mappedCategory = "人情";
                break;
            case "育儿":
                mappedCategory = "亲子";
                break;
            case "生活":
                mappedCategory = "其他";
                break;
        }
        
        return CategoryIconMapper.getIconForCategory(mappedCategory);
    }
    
    private void showWarningDialog(BillBean bill) {
        new PromptDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除这条账单记录吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    deleteBill(bill);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void deleteBill(BillBean bill) {
        BillDatabaseHelper billHelper = new BillDatabaseHelper(this);
        String selection = BillDatabaseHelper.COLUMN_ID + "=?";
        String[] selectionArgs = {String.valueOf(bill.getBillId())};
        
        billHelper.deleteBill(selection, selectionArgs, new BillDatabaseHelper.BillDeleteCallback() {
            @Override
            public void onDeleteSuccess(int rowsDeleted) {
                runOnUiThread(() -> {
                    if (rowsDeleted > 0) {
                        Log.d("CategoriesBillViewActivity", "删除账单成功");
                        refreshBillData();
                        // 通知首页刷新数据
                        notifyHomePageRefresh();
                        if (mDialog != null) {
                            mDialog.dismiss();
                        }
                    }
                });
            }
            
            @Override
            public void onDeleteError(String error) {
                runOnUiThread(() -> {
                    Log.e("CategoriesBillViewActivity", "删除账单失败: " + error);
                });
            }
        });
    }
    
    private void refreshBillData() {
        getCategoryBill();
    }
    
    // 通知首页刷新数据
    private void notifyHomePageRefresh() {
        // 通过Intent返回结果，告知MainActivity需要刷新数据
        Intent resultIntent = new Intent();
        resultIntent.putExtra("refresh_needed", true);
        setResult(Activity.RESULT_OK, resultIntent);
    }
}