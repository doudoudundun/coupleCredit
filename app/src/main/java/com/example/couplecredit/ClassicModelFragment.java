package com.example.couplecredit;

import android.app.Dialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClassicModelFragment extends Fragment {
    private RecyclerView rvBillList;
    private BillAdapter billAdapter;
    private List<Object> displayItems; // 混合数据：String(日期) 和 BillBean
    private List<BillBean> billItems;
    private TextView tvMonthTitle;
    private int currentYear;
    private int currentMonth;
    private TextView tvExpenseAmount;
    private TextView tvIncomeAmount;


    //private BillProvider billProvider;

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
        billAdapter = new BillAdapter(getContext(), displayItems, new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id){
                // TODO: 跳转到账单详情页面
            }
        });
        rvBillList.setAdapter(billAdapter);
        processAndDisplayData();
        sumAmounts();
    }
    private void initBillData() {
        billItems = new ArrayList<>();
        
        // 从数据库读取账单数据
        firstLoadBills();
    }
    private void loadBillData(int year, int month) {
        billItems.clear();
        String monthPattern = String.format("%04d-%02d-%%", year, month);
        Cursor cursor = getContext().getContentResolver().query(
                Uri.parse(BillProvider.CONTENT_URI + "/bills"),
                null,
                BillDatabaseHelper.COLUMN_DATE + " LIKE ?",
                new String[]{monthPattern},
                BillDatabaseHelper.COLUMN_DATE + " DESC"
        );

        if (cursor != null && cursor.moveToFirst()) {
            do {
                double amount = cursor.getDouble(cursor.getColumnIndexOrThrow(BillDatabaseHelper.COLUMN_AMOUNT));
                String dateStr = cursor.getString(cursor.getColumnIndexOrThrow(BillDatabaseHelper.COLUMN_DATE));
                String timeStr = cursor.getString(cursor.getColumnIndexOrThrow(BillDatabaseHelper.COLUMN_TIME));
                int userId = cursor.getInt(cursor.getColumnIndexOrThrow(BillDatabaseHelper.USER_ID));
                String type = cursor.getString(cursor.getColumnIndexOrThrow(BillDatabaseHelper.COLUMN_TYPE));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(BillDatabaseHelper.COLUMN_TITLE));
                int incomeType = cursor.getInt(cursor.getColumnIndexOrThrow(BillDatabaseHelper.COLUMN_INCOME_TYPE));

                // 解析日期字符串 (格式: 2025-08-15)
                String[] dateParts = dateStr.split("-");
                int cur_year = Integer.parseInt(dateParts[0]);
                int cur_month = Integer.parseInt(dateParts[1]);
                int day = Integer.parseInt(dateParts[2]);

                // 根据类型设置图标
                int iconResId = getIconForCategory(type);

                billItems.add(new BillBean(amount, cur_year, cur_month, day, userId, type, title, iconResId, incomeType, timeStr));
            } while (cursor.moveToNext());
            cursor.close();
        }
    }
    private void firstLoadBills() {
        // 先检查数据库是否为空，如果为空则插入示例数据
        checkAndInsertSampleData();
        
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
            if (billAdapter != null) {
                billAdapter.notifyDataSetChanged();
            }
        }
    }
    
    private void checkAndInsertSampleData() {
        // 检查数据库是否为空
        Cursor cursor = getContext().getContentResolver().query(
            Uri.parse(BillProvider.CONTENT_URI + "/bills"),
            new String[]{"COUNT(*) as count"},
            null,
            null,
            null
        );
        
        boolean isEmpty = true;
        if (cursor != null && cursor.moveToFirst()) {
            int count = cursor.getInt(0);
            isEmpty = (count == 0);
            cursor.close();
        }
        
        // 如果数据库为空，插入示例数据
        if (isEmpty) {
            insertSampleData();
        }
    }
    
    private void insertSampleData() {
        // 插入示例账单数据
        Utils.insertBill(getContext(),1, "午餐聚餐", "餐饮", 25.80, "2025-08-15", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),2, "地铁出行", "交通", 12.00, "2025-08-14", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),3, "日用品采购", "购物", 35.50, "2025-08-13", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),1, "电影票", "娱乐", 68.0, "2025-08-12", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),2, "咖啡", "餐饮", 45.0, "2025-08-11", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),3, "工资收入", "收入", 5000.0, "2025-08-10", "00:00:00", 1); // 收入
        Utils.insertBill(getContext(),1, "午餐聚餐", "餐饮", 258.0, "2025-08-15", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),2, "地铁出行", "交通", 12.00, "2025-08-14", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),3, "兼职收入", "收入", 800.0, "2025-08-13", "00:00:00", 1); // 收入
        Utils.insertBill(getContext(),1, "电影票", "娱乐", 68.0, "2025-08-12", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),2, "咖啡", "餐饮", 45.0, "2025-08-11", "00:00:00", 0); // 支出
        Utils.insertBill(getContext(),3, "水电费", "生活", 180.0, "2025-07-10", "00:00:00", 0); // 支出
    }
    
//    private void insertBill(int userId, String title, String type, double amount, String date, int incomeType) {
//        android.content.ContentValues values = new android.content.ContentValues();
//        values.put(BillDatabaseHelper.USER_ID, userId);
//        values.put(BillDatabaseHelper.COLUMN_TITLE, title);
//        values.put(BillDatabaseHelper.COLUMN_TYPE, type);
//        values.put(BillDatabaseHelper.COLUMN_AMOUNT, amount);
//        values.put(BillDatabaseHelper.COLUMN_DATE, date);
//        values.put(BillDatabaseHelper.COLUMN_INCOME_TYPE, incomeType);
//
//        getContext().getContentResolver().insert(Uri.parse(BillProvider.CONTENT_URI + "/bills"), values);
//    }
    
    private int getIconForCategory(String category) {
        switch (category) {
            case "餐饮":
                return R.drawable.ic_money;
            case "交通":
            case "生活":
                return R.drawable.ic_report;
            case "购物":
                return R.drawable.ic_favorite;
            case "娱乐":
                return R.drawable.ic_profile;
            default:
                return R.drawable.ic_money;
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
        Dialog dialog = new Dialog(getContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_date_picker);

        // 获取对话框中的控件
        NumberPicker yearPicker = dialog.findViewById(R.id.np_year);
        NumberPicker monthPicker = dialog.findViewById(R.id.np_month);
        TextView tvCancel = dialog.findViewById(R.id.tv_cancel);
        TextView tvConfirm = dialog.findViewById(R.id.tv_confirm);

        // 设置年份选择器
        yearPicker.setMinValue(2020);
        yearPicker.setMaxValue(2080);
        yearPicker.setValue(currentYear);

        // 设置月份选择器
        String[] monthDisplayValues = {"01", "02", "03", "04", "05", "06",
                "07", "08", "09", "10", "11", "12"};
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setDisplayedValues(monthDisplayValues);
        monthPicker.setValue(currentMonth);

        // 取消按钮
        tvCancel.setOnClickListener(v -> dialog.dismiss());

        // 确认按钮
        tvConfirm.setOnClickListener(v -> {
            currentYear = yearPicker.getValue();
            currentMonth = monthPicker.getValue();
            updateMonthTitle();
            //更新recyclerView显示
            loadBillData(currentYear,currentMonth);
            processAndDisplayData();
            sumAmounts();
            dialog.dismiss();
        });

        dialog.show();
    }
}
