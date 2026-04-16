package com.example.couplecredit.activity;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.model.BillBean;
import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BillAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.utils.BillUtils;
import com.example.couplecredit.utils.CategoryIconMapper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
    private AlertDialog mDialog;
    private View dialogView;
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
    
    private BillBean toBillBean(AuthApiModels.BillData bill) {
        String dateStr = bill.date;
        if (dateStr != null && dateStr.contains("T")) {
            dateStr = dateStr.substring(0, 10);
        }
        String[] dateParts = dateStr.split("-");
        int year = Integer.parseInt(dateParts[0]);
        int month = Integer.parseInt(dateParts[1]);
        int day = Integer.parseInt(dateParts[2]);
        String billCategoryName = bill.type;
        return new BillBean(
                bill.billId,
                bill.amount,
                year,
                month,
                day,
                bill.owner,
                bill.userId,
                billCategoryName,
                "",
                getIconForCategory(billCategoryName),
                bill.incomeType,
                bill.time,
                bill.title,
                bill.isHelp
        );
    }

    private void getCategoryBill() {
        UserInfoManager.getCurrentUserInfo(this, new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                int queryYear = filterYear > 0 ? filterYear : new Date().getYear() + 1900;
                int queryMonth = filterMonth > 0 ? filterMonth : new Date().getMonth() + 1;

                AuthApiClient.queryBills(CategoriesBillViewActivity.this, userId, queryYear, queryMonth, new AuthApiClient.BillsQueryCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.BillsQueryResponse response) {
                        runOnUiThread(() -> {
                            List<BillBean> bills = new ArrayList<>();
                            if (response != null && response.data != null && response.data.bills != null) {
                                for (AuthApiModels.BillData bill : response.data.bills) {
                                    if (bill == null) {
                                        continue;
                                    }
                                    if (!"全部".equals(categoryName) && !categoryName.equals(bill.type)) {
                                        continue;
                                    }
                                    try {
                                        bills.add(toBillBean(bill));
                                    } catch (Exception e) {
                                        Log.e("CategoriesBillViewActivity", "解析账单失败: " + e.getMessage(), e);
                                    }
                                }
                            }
                            processBillResults(bills);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            Log.e("CategoriesBillViewActivity", "查询账单失败: " + error);
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
                    displayItems.clear();
                    billAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void processBillResults(List<BillBean> results) {
        displayItems.clear();
        billItems = new ArrayList<>();

        Map<String, List<BillBean>> dateGroups = new HashMap<>();

        for (BillBean bill : results) {
            billItems.add(bill);
            String date = String.format(Locale.getDefault(), "%04d-%02d-%02d", bill.getYear(), bill.getMonth(), bill.getDay());
            if (!dateGroups.containsKey(date)) {
                dateGroups.put(date, new ArrayList<>());
            }
            dateGroups.get(date).add(bill);
        }

        List<String> sortedDates = new ArrayList<>(dateGroups.keySet());
        sortedDates.sort((d1, d2) -> d2.compareTo(d1));

        for (String date : sortedDates) {
            List<BillBean> billsForDate = dateGroups.get(date);
            billsForDate.sort((b1, b2) -> b2.getTime().compareTo(b1.getTime()));
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

        dialogView = getLayoutInflater().inflate(R.layout.dialog_layout, null);
        mDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("账单详情")
                .setView(dialogView)
                .create();
        mDialog.show();

        ImageView ivCategoryIcon = dialogView.findViewById(R.id.iv_category_icon);
        TextView tvCategoryName = dialogView.findViewById(R.id.tv_category_name);
        TextView tvDate = dialogView.findViewById(R.id.tv_date);
        TextView tvFare = dialogView.findViewById(R.id.tv_fare);
        TextView tvNoteContent = dialogView.findViewById(R.id.tv_note_content);
        EditText etFare = dialogView.findViewById(R.id.et_fare);
        EditText etNoteContent = dialogView.findViewById(R.id.et_note_content);
        Button btnDelete = dialogView.findViewById(R.id.btn_delete);
        Button btnEdit = dialogView.findViewById(R.id.btn_edit);
        ImageButton btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        ImageButton btnCancel = dialogView.findViewById(R.id.btn_cancel);
        LinearLayout llNoteCard = dialogView.findViewById(R.id.ll_note_card);

        if (ivCategoryIcon != null) {
            int iconResId = getIconForCategory(categoryName);
            ivCategoryIcon.setImageResource(iconResId);
            ivCategoryIcon.setBackground(null);
        }
        if (tvCategoryName != null) {
            tvCategoryName.setText(incomeType + "-" + categoryName);
        }
        if (tvDate != null) {
            tvDate.setText(date);
        }
        if (tvFare != null) {
            tvFare.setText("￥ " + String.format("%.2f", fare));
        }
        if (etFare != null) {
            etFare.setText("￥" + String.format("%.2f", fare));
        }

        String noteTitle = bill.getTitle();
        if (noteTitle != null && !noteTitle.trim().isEmpty() && !noteTitle.equals(categoryName)) {
            if (llNoteCard != null) {
                llNoteCard.setVisibility(View.VISIBLE);
            }
            if (tvNoteContent != null) {
                tvNoteContent.setText(noteTitle);
            }
            if (etNoteContent != null) {
                etNoteContent.setText(noteTitle);
            }
        } else if (llNoteCard != null) {
            llNoteCard.setVisibility(View.GONE);
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> showWarningDialog(bill));
        }
        if (btnEdit != null) {
            btnEdit.setOnClickListener(v -> BillUtils.enterEditMode(this, mDialog, tvDate, tvFare, tvNoteContent,
                    etFare, etNoteContent, btnEdit, btnDelete, btnConfirm, btnCancel));
        }
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                String currentDate = tvDate.getText().toString();
                String fareText = etFare.getText().toString();
                double currentFare = fareText.startsWith("￥") ? Double.parseDouble(fareText.substring(1)) : Double.parseDouble(fareText);
                String currentNoteContent = etNoteContent.getText().toString();
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String currentTime = timeFormat.format(new Date());

                BillUtils.updateBill(this, bill, currentDate, currentFare, currentNoteContent, currentTime, new BillUtils.UpdateBillCallback() {
                    @Override
                    public void onUpdateSuccess(int rowsAffected) {
                        runOnUiThread(() -> {
                            if (rowsAffected > 0) {
                                refreshBillData();
                                notifyHomePageRefresh();
                                mDialog.dismiss();
                            }
                        });
                    }

                    @Override
                    public void onUpdateError(String error) {
                        runOnUiThread(() -> Log.e("CategoriesBillViewActivity", "更新账单失败: " + error));
                    }
                });
                BillUtils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent, btnEdit, btnDelete, btnConfirm, btnCancel);
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (etFare != null) {
                    etFare.setText("￥" + String.format("%.2f", fare));
                }
                if (etNoteContent != null) {
                    etNoteContent.setText(noteTitle != null ? noteTitle : "");
                }
                if (tvFare != null) {
                    tvFare.setText("￥ " + String.format("%.2f", fare));
                }
                if (tvNoteContent != null) {
                    tvNoteContent.setText(noteTitle != null ? noteTitle : "");
                }
                BillUtils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent, btnEdit, btnDelete, btnConfirm, btnCancel);
            });
        }
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除这条账单记录吗？")
                .setPositiveButton("确定", (dialog, which) -> deleteBill(bill))
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }
    
    private void deleteBill(BillBean bill) {
        BillUtils.deleteBill(this, bill, new BillUtils.DeleteBillCallback() {
            @Override
            public void onDeleteSuccess(int rowsDeleted) {
                runOnUiThread(() -> {
                    if (rowsDeleted > 0) {
                        Log.d("CategoriesBillViewActivity", "删除账单成功");
                        refreshBillData();
                        notifyHomePageRefresh();
                        if (mDialog != null) {
                            mDialog.dismiss();
                        }
                    }
                });
            }

            @Override
            public void onDeleteError(String error) {
                runOnUiThread(() -> Log.e("CategoriesBillViewActivity", "删除账单失败: " + error));
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