package com.example.couplecredit.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.adapter.BillAdapter;
import com.example.couplecredit.BillBean;
import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.R;
import com.example.couplecredit.function.Utils;
import com.example.couplecredit.function.UserInfoManager;
import com.transsion.widgetslib.dialog.PromptDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ClassicModelFragment extends Fragment implements BillAdapter.OnItemClickListener {
    private RecyclerView rvBillList;
    private BillAdapter billAdapter;
    private List<Object> displayItems; // 混合数据：String(日期) 和 BillBean
    private List<BillBean> billItems;
    private TextView tvMonthTitle;
    private int currentYear;
    private int currentMonth;
    private TextView tvExpenseAmount;
    private TextView tvIncomeAmount;
    private TextView tvLoginPrompt;

    private PromptDialog mDialog;



    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.classic_model_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 获取当前年月
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH从0开始
        //String beParser = currentYear + "-" + (currentMonth < 10 ? "0" + currentMonth : currentMonth);

        // 初始化视图
        tvMonthTitle = view.findViewById(R.id.tv_month_title);
        rvBillList = view.findViewById(R.id.rv_bill_list);
        tvExpenseAmount = view.findViewById(R.id.tv_expense_amount);
        tvIncomeAmount = view.findViewById(R.id.tv_income_amount);
        tvLoginPrompt = view.findViewById(R.id.tv_login_prompt);
        
        // 设置卡片点击监听器
        LinearLayout llExpenseCard = view.findViewById(R.id.ll_expense_card);
        LinearLayout llIncomeCard = view.findViewById(R.id.ll_income_card);
        
        llExpenseCard.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity != null) {
                // 跳转到ReportFragment并同步月份
                ReportFragment reportFragment = mainActivity.getReportFragment();
                mainActivity.showFragment(reportFragment);

                // 更新底部导航栏选中状态
                com.transsion.widgetslib.widget.FootOperationBar footBar =
                        (com.transsion.widgetslib.widget.FootOperationBar) mainActivity.findViewById(R.id.bottom_nav);
                if (footBar != null) {
                    footBar.setItemSelectState(2);
                }
                // 更新ReportFragment的月份显示
                reportFragment.updateDisplay(currentYear, currentMonth, "expense");
            }
        });
        
        llIncomeCard.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity != null) {
                // 跳转到ReportFragment并同步月份
                ReportFragment reportFragment = mainActivity.getReportFragment();
                mainActivity.showFragment(reportFragment);
                // 更新ReportFragment的月份显示
                reportFragment.updateDisplay(currentYear, currentMonth, "income");
                // 更新底部导航栏选中状态
                com.transsion.widgetslib.widget.FootOperationBar footBar = 
                    (com.transsion.widgetslib.widget.FootOperationBar) mainActivity.findViewById(R.id.bottom_nav);
                if (footBar != null) {
                    footBar.setItemSelectState(2);
                }
            }
        });


        // 移除初始化时的弹窗创建，改为在点击时创建


        //统计收入和支出

        updateMonthTitle();
        tvMonthTitle.setOnClickListener(v -> showDatePickerDialog());

        // 初始化RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rvBillList.setLayoutManager(layoutManager);

        // 初始化数据
        initBillData();

        // 设置适配器
        displayItems = new ArrayList<>();
        billAdapter = new BillAdapter(getContext(), displayItems, new BillAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position, BillBean bill) {
                processDialog(bill);
            }
        });
        rvBillList.setAdapter(billAdapter);
        processAndDisplayData();
        sumAmounts();
    }
    private void initBillData() {
        billItems = new ArrayList<>();
        displayItems = new ArrayList<>();
        
        // 初始化适配器
        billAdapter = new BillAdapter(getContext(), displayItems, this);
        rvBillList.setAdapter(billAdapter);
        
        // 从数据库读取账单数据
        firstLoadBills();
    }
    private void loadBillData(int year, int month) {
        billItems.clear();
        String monthPattern = String.format("%04d-%02d-%%", year, month);
        
        Log.d("ClassicModelFragment", "开始加载账单数据: " + year + "-" + month);
        long loadStartTime = System.currentTimeMillis();
        
        // 首先检查用户是否已登录
        if (!UserInfoManager.isUserLoggedIn(getContext())) {
            // 用户未登录
            // 清空账单数据并更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    processAndDisplayData();
                    sumAmounts();
                    if (billAdapter != null) {
                        billAdapter.notifyDataSetChanged();
                    }
                    // 可以在这里添加"请登录查看账单"的提示
                    showLoginPrompt();
                });
            }
            return;
        }
        
        // 获取当前用户信息和relationship状态
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                // 获取用户信息成功
                
                // 隐藏登录提示，显示账单列表
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> hideLoginPrompt());
                }
                
                BillDatabaseHelper billHelper = new BillDatabaseHelper(getContext());
                // 使用新的筛选查询方法
                String dateSelection = "date LIKE ?";
                String[] dateSelectionArgs = new String[]{monthPattern};
                
                Log.d("ClassicModelFragment", "执行数据库查询，用户ID: " + userId + ", 关系ID: " + relationshipId);
                billHelper.queryBillsWithUserFilter(userId, relationshipId, dateSelection, dateSelectionArgs, new BillDatabaseHelper.QueryCallback() {
            @Override
            public void onSuccess(List<Map<String, Object>> results) {
                long dataLoadTime = System.currentTimeMillis();
                Log.d("ClassicModelFragment", "数据库查询完成，耗时: " + (dataLoadTime - loadStartTime) + "ms, 结果数: " + results.size());
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        long uiStartTime = System.currentTimeMillis();
                        for (Map<String, Object> row : results) {
                            long billId = ((Number) row.get("_id")).longValue();
                            double amount = (Double) row.get("amount");
                            String dateStr = (String) row.get("date");
                            String timeStr = (String) row.get("time");
                            Integer ownerObj = (Integer) row.get("owner");
                            int owner = ownerObj != null ? ownerObj : 0;
                            // 处理owner字段
                            Integer userIdObj = (Integer) row.get("userId");
                            int userId = userIdObj != null ? userIdObj : 0;
                            String type = (String) row.get("type");
                            String title = (String) row.get("title");
                            Integer incomeTypeObj = (Integer) row.get("income_type");
                            int incomeType = incomeTypeObj != null ? incomeTypeObj : 0;
                            Integer isHelpObj = (Integer) row.get("is_help");
                            int isHelp = isHelpObj != null ? isHelpObj : 0;

                            // 解析日期字符串 (格式: 2025-08-15)
                            String[] dateParts = dateStr.split("-");
                            int cur_year = Integer.parseInt(dateParts[0]);
                            int cur_month = Integer.parseInt(dateParts[1]);
                            int day = Integer.parseInt(dateParts[2]);

                            // 根据类型设置图标
                            int iconResId = getIconForCategory(type);

                            billItems.add(new BillBean(billId, amount, cur_year, cur_month, day, owner, userId, type, title, iconResId, incomeType, timeStr, title, isHelp));
                        }
                        processAndDisplayData();
                        sumAmounts();
                        // 通知适配器数据已更新
                        if (billAdapter != null) {
                            billAdapter.notifyDataSetChanged();
                        }
                        
                        long uiEndTime = System.currentTimeMillis();
                        Log.d("ClassicModelFragment", "UI更新完成，耗时: " + (uiEndTime - uiStartTime) + "ms");
                        Log.d("ClassicModelFragment", "总加载耗时: " + (uiEndTime - loadStartTime) + "ms");
                    });
                }
            }

            @Override
            public void onError(String error) {
                long errorTime = System.currentTimeMillis();
                Log.e("ClassicModelFragment", "数据库查询失败，耗时: " + (errorTime - loadStartTime) + "ms, 错误: " + error);
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Log.e("ClassicModelFragment", "加载账单数据失败: " + error);
                        // 可以显示错误提示给用户
                    });
                }
            }
        });
            }
            
            @Override
            public void onError(String error) {
                Log.e("ClassicModelFragment", "获取用户信息失败: " + error);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        // 清空账单数据并更新UI
                        processAndDisplayData();
                        sumAmounts();
                        if (billAdapter != null) {
                            billAdapter.notifyDataSetChanged();
                        }
                        showLoginPrompt();
                    });
                }
            }
        });
    }
    private void firstLoadBills() {
        // 获取当前年月
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH 从0开始
        
        loadBillData(currentYear, currentMonth);
    }
    
    // 公共方法：刷新当前月份的账单数据
    public void refreshBillData() {
        if (getContext() != null) {
            loadBillData(currentYear, currentMonth);
            processAndDisplayData();
            sumAmounts();
            updateMonthTitle();
            if (billAdapter != null) {
                billAdapter.notifyDataSetChanged();
            }
        }
    }
    

    

    
    private int getIconForCategory(String category) {
        switch (category) {
            case "餐品":
                return R.drawable.img_category_food;
            case "饮品":
                return R.drawable.img_category_drink;
            case "水果":
                return R.drawable.img_category_fruit;
            case "购物":
                return R.drawable.img_category_shopping;
            case "交通":
                return R.drawable.img_category_transport;
            case "住宿":
                return R.drawable.img_category_hotel;
            case "日常":
                return R.drawable.img_category_daily;
            case "学习":
                return R.drawable.img_category_study;
            case "娱乐":
                return R.drawable.img_category_entertainment;
            case "化妆":
                return R.drawable.img_category_cosmetic;
            case "旅游":
                return R.drawable.img_category_travel;
            case "医疗":
                return R.drawable.img_category_medical;
            case "会员":
                return R.drawable.img_category_member;
            case "通讯":
                return R.drawable.img_category_communication;
            case "人情":
            case "社交":
                return R.drawable.img_category_social;
            case "投资":
                return R.drawable.img_category_investment;
            case "亲子":
            case "育儿":
                return R.drawable.img_category_parenting;
            case "宠物":
                return R.drawable.img_category_pet;
            case "装修":
                return R.drawable.img_category_decoration;
            // 收入分类
            case "工资":
                return R.drawable.img_category_salary;
            case "礼金":
                return R.drawable.img_category_cashgift;
            case "兼职":
                return R.drawable.img_category_parttime;
            case "理财":
                return R.drawable.img_category_financial;
            case "其他":
            case "生活":
                return R.drawable.img_category_other;
            default:
                return R.drawable.img_category_other;
        }
    }
    private void processAndDisplayData() {
        displayItems.clear();
            // 经典模式：按日期分组显示
            // 按日期倒序排序
            Collections.sort(billItems, new Comparator<BillBean>() {
                @Override
                public int compare(BillBean b1, BillBean b2) {
                    // 先按年份倒序
                    if (b1.getYear() != b2.getYear()) {
                        return Integer.compare(b2.getYear(), b1.getYear());
                    }
                    // 再按月份倒序
                    if (b1.getMonth() != b2.getMonth()) {
                        return Integer.compare(b2.getMonth(), b1.getMonth());
                    }
                    // 再按日期倒序
                    if (b1.getDay() != b2.getDay()) {
                        return Integer.compare(b2.getDay(), b1.getDay());
                    }
                    // 日期相同时按时间倒序排列
                    if (!b1.getTime().equals(b2.getTime())) {
                        return b2.getTime().compareTo(b1.getTime());
                    }
                    // 时间相同时按种类名首字母排序
                    return b1.getCategoryName().compareToIgnoreCase(b2.getCategoryName());
                }
            });

            // 按日期分组
            Map<String, List<BillBean>> dateGroups = new LinkedHashMap<>();
            for (BillBean bill : billItems) {
                String dateKey = String.format("%02d.%02d", bill.getMonth(), bill.getDay());
                if (!dateGroups.containsKey(dateKey)) {
                    dateGroups.put(dateKey, new ArrayList<>());
                }
                dateGroups.get(dateKey).add(bill);
            }

            // 将分组数据添加到displayItems
            for (Map.Entry<String, List<BillBean>> entry : dateGroups.entrySet()) {
                Map<String, List<BillBean>> dateGroup = new HashMap<>();
                dateGroup.put(entry.getKey(), entry.getValue());
                displayItems.add(dateGroup);
            }
            billAdapter.notifyDataSetChanged();
    }
    private void sumAmounts() {
        double totalIncome = 0;
        double totalExpense = 0;
        for (BillBean bill : billItems) {
            if (bill.getIncomeType() == 1) {
                totalIncome += bill.getFare();
            } else {
                totalExpense += bill.getFare();
            }
        }
        tvIncomeAmount.setText("￥ " + String.format("%.2f", totalIncome));
        tvExpenseAmount.setText("￥ " + String.format("%.2f", totalExpense));
    }
    private void updateMonthTitle() {
        String monthText = getMonthText(currentMonth);
        if (monthText != null) {
            tvMonthTitle.setText("我们的" + monthText + " >");
        } else {
            Log.e("ClassicModelFragment", "monthTitleTextView is null!");
        }
    }

    private String getMonthText(int month) {
        String[] months = {"1月", "2月", "3月", "4月", "5月", "6月",
                "7月", "8月", "9月", "10月", "11月", "12月"};
        return months[month - 1];
    }
    private void showDatePickerDialog() {
        Utils.showDatePickerDialog(getContext(), currentYear, currentMonth, (selectedYear, selectedMonth)->{
            currentYear = selectedYear;
            currentMonth = selectedMonth;
            updateMonthTitle();
            //更新recyclerView显示
            loadBillData(currentYear,currentMonth);
            processAndDisplayData();
            sumAmounts();
        });
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
        mDialog = new PromptDialog.Builder(getContext())
                .setTitle("账单详情")
                .setView(R.layout.dialog_layout)
                .show();

        // 获取对话框中的视图组件
        ImageView ivCategoryIcon = mDialog.findViewById(R.id.iv_category_icon);
        TextView tvCategoryName = mDialog.findViewById(R.id.tv_category_name);
        TextView tvDate = mDialog.findViewById(R.id.tv_date);
        TextView tvFare = mDialog.findViewById(R.id.tv_fare);
        TextView tvNoteContent = mDialog.findViewById(R.id.tv_note_content);
        

//        EditText etDate = mDialog.findViewById(R.id.et_date);
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
//        if (etDate != null) etDate.setText(date);
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
            // 进入修改模式
            Utils.enterEditMode(getContext(), mDialog, tvDate, tvFare, tvNoteContent,
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
            
            Utils.updateBill(getContext(), bill, currentDate, currentFare, currentNoteContent, currentTime, new Utils.UpdateBillCallback() {
                @Override
                public void onUpdateSuccess(int rowsAffected) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (rowsAffected > 0) {
                                // 更新成功，刷新数据
                                refreshBillData();
                                // 刷新ReportFragment的图表数据
                                if (getActivity() instanceof MainActivity) {
                                    MainActivity mainActivity = (MainActivity) getActivity();
                                    ReportFragment reportFragment = mainActivity.getReportFragment();
                                    if (reportFragment != null) {
                                        reportFragment.refreshChartData();
                                    }
                                }
                            }
                        });
                    }
                }

                @Override
                public void onUpdateError(String error) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Log.e("ClassicModelFragment", "更新账单失败: " + error);
                        });
                    }
                }
            });
            
            Utils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent,
                        btnEdit, btnDelete, btnConfirm, btnCancel);
        });
        
        if (btnCancel != null) btnCancel.setOnClickListener(v -> {
            // 取消修改，恢复原始数据
//            if (etDate != null) etDate.setText(date);
            if (etFare != null) etFare.setText("￥" + String.format("%.2f", fare));
            if (etNoteContent != null) etNoteContent.setText(noteTitle != null ? noteTitle : "");
            
            // 更新TextView显示原始数据
            if (tvFare != null) tvFare.setText("￥ " + String.format("%.2f", fare));
            if (tvNoteContent != null) tvNoteContent.setText(noteTitle != null ? noteTitle : "");
            
            Utils.exitEditMode(mDialog,tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent,
                        btnEdit, btnDelete, btnConfirm, btnCancel);
        });
    }
    
    private void showWarningDialog(BillBean bill) {
        new PromptDialog.Builder(getContext())
                .setTitle("删除账单（此举不可逆）")
                .setPositiveButton("确定", (dialog, which) ->{
                    Utils.deleteBill(getContext(), bill, new Utils.DeleteBillCallback() {
                        @Override
                        public void onDeleteSuccess(int rowsDeleted) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (rowsDeleted > 0) {
                                        // 删除成功，刷新数据
                                        refreshBillData();
                                        // 刷新ReportFragment的图表数据
                                        if (getActivity() instanceof MainActivity) {
                                            MainActivity mainActivity = (MainActivity) getActivity();
                                            ReportFragment reportFragment = mainActivity.getReportFragment();
                                            if (reportFragment != null) {
                                                reportFragment.refreshChartData();
                                            }
                                        }
                                        // 关闭主对话框
                                        if (mDialog != null) {
                                            mDialog.dismiss();
                                        }
                                    }
                                });
                            }
                        }
                        
                        @Override
                        public void onDeleteError(String error) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    Log.e("ClassicModelFragment", "删除账单失败: " + error);
                                });
                            }
                        }
                    });
                    dialog.dismiss();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }
    
    private void showLoginPrompt() {
        // 显示登录提示
        if (tvLoginPrompt != null) {
            tvLoginPrompt.setVisibility(View.VISIBLE);
        }
        if (rvBillList != null) {
            rvBillList.setVisibility(View.GONE);
        }
    }
    
    private void hideLoginPrompt() {
        if (tvLoginPrompt != null) {
            tvLoginPrompt.setVisibility(View.GONE);
        }
        if (rvBillList != null) {
            rvBillList.setVisibility(View.VISIBLE);
        }
    }
    
    @Override
    public void onItemClick(View view, int position, BillBean bill) {
        // 处理账单项点击事件
        processDialog(bill);
    }
}
