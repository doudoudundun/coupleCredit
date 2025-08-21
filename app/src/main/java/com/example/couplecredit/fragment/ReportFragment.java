package com.example.couplecredit.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.couplecredit.BillDatabaseHelper;
import com.example.couplecredit.BillProvider;
import com.example.couplecredit.R;
import com.example.couplecredit.function.Utils;
import com.transsion.widgetslib.widget.OSSegmentedTab;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import android.database.Cursor;
import android.net.Uri;

public class ReportFragment extends Fragment {
    private int currentYear;
    private int currentMonth;
    private TextView tv_month_choose;
    private TextView tv_trend_title;
    private TextView tv_remainer;
    private OSSegmentedTab segmentedTab;
    private List<String> currentTabs = new ArrayList<>();
    private LineChart TrendChart;
    private LinearLayout TrendContainer;
    private int income_type;

    private OSSegmentedTab.OnTabSelectedListener onTabSelectedListener = new OSSegmentedTab.OnTabSelectedListener() {
        @Override
        public void onTabSelected(int position) {
            // 根据选中的标签页更新内容
            if (position == 0) {
                // 显示支出内容
                income_type = 0;
                tv_trend_title.setText("支出趋势");
                loadTrendData();
                tv_remainer.setText("结余：￥" + String.format("%.2f", getRemainer()));
            } else if (position == 1) {
                // 显示收入内容
                income_type = 1;
                tv_trend_title.setText("收入趋势");
                loadTrendData();
            }
        }
    };
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_report, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 在这里初始化UI组件和设置监听器
        // 例如：图表、统计数据等
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1;

        Bundle args = getArguments();
        if (args != null) {
            String type = args.getString("type");
            int year = args.getInt("year");
            int month = args.getInt("month");
            // 使用这些数据
            currentYear = year;
            currentMonth = month;
        }
        tv_trend_title = view.findViewById(R.id.tv_trend_title);
        tv_month_choose = view.findViewById(R.id.tv_month_choose);
        tv_remainer = view.findViewById(R.id.tv_remainer);
        tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        tv_month_choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.showDatePickerDialog(getActivity(), currentYear, currentMonth, (selectedYear, selectedMonth)-> {
                    tv_month_choose.setText(selectedYear + "年" + selectedMonth + "月 >");
                    currentYear = selectedYear;
                    currentMonth = selectedMonth;
                    loadTrendData();
                });
            }
        });
        segmentedTab = view.findViewById(R.id.segmented_tab);
        if (currentTabs.isEmpty()) {
            currentTabs.add("支出");
            currentTabs.add("收入");
        }
        setupSegmentedTab();
        
        // 初始化收入趋势图表
        TrendContainer = view.findViewById(R.id.ll_trend_container);
        TrendChart = view.findViewById(R.id.trend_chart);
        setupTrendChart();

    }
    
    // 公共方法：更新月份显示
    public void updateDisplay(int year, int month, String type) {
        currentYear = year;
        currentMonth = month;
        if (tv_month_choose != null) {
            tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        }
        loadTrendData();
    }


    private void setupSegmentedTab() {
        segmentedTab.addTabs(currentTabs);
        // 设置选中监听
        segmentedTab.setOnTabSelectedListener(onTabSelectedListener);
    }
    
    private void setupTrendChart() {
        // 配置图表样式
        TrendChart.getDescription().setEnabled(false);
        TrendChart.setTouchEnabled(true);
        TrendChart.setDragEnabled(true);
        TrendChart.setScaleEnabled(true);
        TrendChart.setPinchZoom(true);
        
        // 配置X轴
        XAxis xAxis = TrendChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setAxisMinimum(1f);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value) + "日";
            }
        });
        
        // 配置Y轴
        YAxis leftAxis = TrendChart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        TrendChart.getAxisRight().setEnabled(false);
    }
    
    private void loadTrendData() {
        // 模拟收入趋势数据（实际应从数据库获取）
        List<com.github.mikephil.charting.data.Entry> entries = new ArrayList<>();
        // 获取当前月份的天数
        Calendar calendar = Calendar.getInstance();
        calendar.set(currentYear, currentMonth - 1, 1);
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        
        // 为每一天都添加数据点，没有账单的日子显示0
        for (int day = 1; day <= daysInMonth; day++) {
            float count = getCountForDay(day, income_type);
            entries.add(new Entry(day, count));
        }
        
        // 现在总是有数据点（包括0值），所以不需要检查空数据
        LineDataSet dataSet = new LineDataSet(entries, "金额");
        if(income_type == 1){
            dataSet.setLabel("收入金额");
            dataSet.setColor(0xFFF44336);
            dataSet.setCircleColor(0xFFF44336);
            dataSet.setFillColor(0xFFF44336);
        }else{
//            LineDataSet dataSet = new LineDataSet(entries, "支出趋势");
            dataSet.setLabel("支出金额");
            dataSet.setColor(0xFF4CAF50); // 绿色
            dataSet.setCircleColor(0xFF4CAF50);
            dataSet.setFillColor(0xFF4CAF50);
        }
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawCircleHole(false);
        dataSet.setValueTextSize(10f);
        dataSet.setDrawFilled(true);
        dataSet.setFillAlpha(50);
        
        LineData lineData = new LineData(dataSet);
        
        // 检查TrendChart是否已初始化
        if (TrendChart != null) {
            TrendChart.setData(lineData);
            // 添加从下到上的动画效果，持续时间300毫秒
            TrendChart.animateY(300);
            TrendChart.invalidate();
        }
    }
    private double getRemainer(){
        //获取当月天数
        Calendar calendar = Calendar.getInstance();
        calendar.set(currentYear, currentMonth - 1, 1);
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);

        double total = 0;
        for (int day = 1; day <= daysInMonth; day++) {
            float count = getCountForDay(day, 1)-getCountForDay(day, 0);
            total += count;
        }
        return total;
    }
    private float getCountForDay(int day, int income_type) {
        // 查询数据库获取指定日期的收入数据
        String dateStr = String.format("%04d-%02d-%02d", currentYear, currentMonth, day);
        
        // 检查Context是否可用
        if (getContext() == null) {
            return 0;
        }
        
        // 构建查询条件：日期匹配且为收入类型相同
        String selection = BillDatabaseHelper.COLUMN_DATE + "=? AND " +
                          BillDatabaseHelper.COLUMN_INCOME_TYPE + "=?";
        String[] selectionArgs= new String[]{dateStr, String.valueOf(income_type)}; // 1表示收入，0表示支出;
        
        Uri uri = Uri.parse(BillProvider.CONTENT_URI + "/bills");
        Cursor cursor = getContext().getContentResolver().query(
            uri, 
            new String[]{BillDatabaseHelper.COLUMN_AMOUNT}, 
            selection, 
            selectionArgs, 
            null
        );
        float totalCount = 0;
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int amountIndex = cursor.getColumnIndex(BillDatabaseHelper.COLUMN_AMOUNT);
                if (amountIndex != -1) {
                    totalCount += cursor.getFloat(amountIndex);
                }
            }
            cursor.close();
        }
        
        return totalCount;
    }
}