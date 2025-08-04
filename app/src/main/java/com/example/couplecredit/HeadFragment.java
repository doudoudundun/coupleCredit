package com.example.couplecredit;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.NumberPicker;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class HeadFragment extends Fragment {
    
    private RecyclerView rvBillList;
    private BillAdapter billAdapter;
    private List<BillBean> billItems;
    private TextView tvMonthTitle;
    private int currentYear;
    private int currentMonth;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_head, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化当前日期
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH从0开始
        
        // 初始化视图
        tvMonthTitle = view.findViewById(R.id.tv_month_title);
        updateMonthTitle();
        
        // 设置月份标题点击事件
        tvMonthTitle.setOnClickListener(v -> showDatePickerDialog());
        
        // 初始化RecyclerView
        rvBillList = view.findViewById(R.id.rv_bill_list);
        rvBillList.setLayoutManager(new LinearLayoutManager(getContext()));
        
        // 初始化数据
        initBillData();
        
        // 设置适配器
        billAdapter = new BillAdapter(billItems);
        rvBillList.setAdapter(billAdapter);
    }
    
    private void initBillData() {
        billItems = new ArrayList<>();
        
        // 添加示例数据，模拟截图中的账单（只显示4个）

    }
    
    private void updateMonthTitle() {
        String monthText = getMonthText(currentMonth);
        tvMonthTitle.setText("我们的" + monthText + " >");
    }
    
    private String getMonthText(int month) {
        String[] months = {"一月", "二月", "三月", "四月", "五月", "六月", 
                          "七月", "八月", "九月", "十月", "十一月", "十二月"};
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
        yearPicker.setMaxValue(2030);
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