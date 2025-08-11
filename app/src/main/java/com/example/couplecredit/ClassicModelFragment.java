package com.example.couplecredit;

import android.app.Dialog;
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
//    private TextView tvClassicMode;
//    private TextView tvChatMode;
    private int currentYear;
    private int currentMonth;

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

        // 初始化视图
        tvMonthTitle = view.findViewById(R.id.tv_month_title);
        rvBillList = view.findViewById(R.id.rv_bill_list);
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
    }
    private void initBillData() {
        billItems = new ArrayList<>();

        // 添加示例数据，模拟账单记录
        billItems.add(new BillBean(25.80, 2025, 8, 15, 1, "餐饮", "午餐聚餐", R.drawable.ic_money));
        billItems.add(new BillBean(12.00, 2025, 8, 14, 2, "交通", "地铁出行", R.drawable.ic_report));
        billItems.add(new BillBean(35.00, 2025, 8, 13, 3, "购物", "日用品采购", R.drawable.ic_favorite));
        billItems.add(new BillBean(68.0, 2025, 8, 12, 1, "娱乐", "电影票", R.drawable.ic_profile));
        billItems.add(new BillBean(45.0, 2025, 8, 11, 2, "餐饮", "咖啡", R.drawable.ic_money));
        billItems.add(new BillBean(180.0, 2025, 8, 10, 3, "生活", "水电费", R.drawable.ic_report));
        billItems.add(new BillBean(258.0, 2025, 8, 15, 1, "餐饮", "午餐聚餐", R.drawable.ic_money));
        billItems.add(new BillBean(12.00, 2025, 8, 14, 2, "交通", "地铁出行", R.drawable.ic_report));
        billItems.add(new BillBean(35.00, 2025, 8, 13, 3, "购物", "日用品采购", R.drawable.ic_favorite));
        billItems.add(new BillBean(68.0, 2025, 8, 12, 1, "娱乐", "电影票", R.drawable.ic_profile));
        billItems.add(new BillBean(45.0, 2025, 8, 11, 2, "餐饮", "咖啡", R.drawable.ic_money));
        billItems.add(new BillBean(180.0, 2025, 7, 10, 3, "生活", "水电费", R.drawable.ic_report));
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
                    // 最后按日期倒序
                    if (b1.getDay() != b2.getDay()) {
                        return Integer.compare(b2.getDay(), b1.getDay());
                    }
                    // 同一天内按种类名首字母排序
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
            dialog.dismiss();
        });

        dialog.show();
    }
}
