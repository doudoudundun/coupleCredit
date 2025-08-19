package com.example.couplecredit;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.transsion.widgetslib.widget.OSSegmentedTab;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class ReportFragment extends Fragment {
    private int currentYear;
    private int currentMonth;
    private TextView tv_month_choose;
    private OSSegmentedTab segmentedTab;
    private List<String> currentTabs = new ArrayList<>();

    private OSSegmentedTab.OnTabSelectedListener onTabSelectedListener = new OSSegmentedTab.OnTabSelectedListener() {
        //todo
        @Override
        public void onTabSelected(int position) {
            if (position == 0) {

            } else if (position == 1) {

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
        tv_month_choose = view.findViewById(R.id.tv_month_choose);
        tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        tv_month_choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.showDatePickerDialog(getActivity(), currentYear, currentMonth, (selectedYear, selectedMonth)-> {
                    tv_month_choose.setText(selectedYear + "年" + selectedMonth + "月 >");
                    //todo 更新月份账单显示
                });
            }
        });
        segmentedTab = view.findViewById(R.id.segmented_tab);
        if (currentTabs.isEmpty()) {
            currentTabs.add("支出");
            currentTabs.add("收入");
        }
        setupSegmentedTab();

    }
    
    // 公共方法：更新月份显示
    public void updateMonthDisplay(int year, int month, String type) {
        currentYear = year;
        currentMonth = month;
        if (tv_month_choose != null) {
            tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        }
        // 这里可以根据type参数做其他处理，比如更新图表数据等
    }

    private void setupSegmentedTab() {
        segmentedTab.addTabs(currentTabs);
        // 设置选中监听
        segmentedTab.setOnTabSelectedListener(onTabSelectedListener);
    }
}