package com.example.couplecredit.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;

public class ReportAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int TYPE_CHART = 0;
    private static final int TYPE_ADDITIONAL = 1;
    
    // 图表ViewHolder接口，用于Fragment与Adapter通信
    public interface ChartViewHolderCallback {
        void onChartViewHolderCreated(ChartViewHolder holder);
    }
    
    // 额外内容ViewHolder接口
    public interface AdditionalViewHolderCallback {
        void onAdditionalViewHolderCreated(AdditionalViewHolder holder);
    }
    
    private ChartViewHolderCallback chartCallback;
    private AdditionalViewHolderCallback additionalCallback;
    
    public ReportAdapter(ChartViewHolderCallback chartCallback, AdditionalViewHolderCallback additionalCallback) {
        this.chartCallback = chartCallback;
        this.additionalCallback = additionalCallback;
    }
    
    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_CHART : TYPE_ADDITIONAL;
    }
    
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_CHART) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_report_chart, parent, false);
            ChartViewHolder holder = new ChartViewHolder(view);
            if (chartCallback != null) {
                chartCallback.onChartViewHolderCreated(holder);
            }
            return holder;
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_report_additional, parent, false);
            AdditionalViewHolder holder = new AdditionalViewHolder(view);
            if (additionalCallback != null) {
                additionalCallback.onAdditionalViewHolderCreated(holder);
            }
            return holder;
        }
    }
    
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        // 绑定数据的逻辑在Fragment中处理
    }
    
    @Override
    public int getItemCount() {
        return 2; // 图表 + 额外内容
    }
    
    // 图表ViewHolder
    public static class ChartViewHolder extends RecyclerView.ViewHolder {
        public LinearLayout llTrendContainer;
        public TextView tvTrendTitle;
        public TextView tvTotalAmount;
        public TextView tvFilterAll, tvFilterSelf, tvFilterPartner, tvFilterShared;
        public LineChart trendChart;
        
        public ChartViewHolder(@NonNull View itemView) {
            super(itemView);
            llTrendContainer = itemView.findViewById(R.id.ll_trend_container);
            tvTrendTitle = itemView.findViewById(R.id.tv_trend_title);
            tvTotalAmount = itemView.findViewById(R.id.tv_total_amount);
            tvFilterAll = itemView.findViewById(R.id.tv_filter_all);
            tvFilterSelf = itemView.findViewById(R.id.tv_filter_self);
            tvFilterPartner = itemView.findViewById(R.id.tv_filter_partner);
            tvFilterShared = itemView.findViewById(R.id.tv_filter_shared);
            trendChart = itemView.findViewById(R.id.trend_chart);
        }
    }
    
    // 额外内容ViewHolder
    public static class AdditionalViewHolder extends RecyclerView.ViewHolder {
        public LinearLayout llAdditionalContainer;
        public TextView tvAdditionalTitle;
        public TextView tvFilterAllPie, tvFilterSelfPie, tvFilterPartnerPie, tvFilterSharedPie;
        public PieChart pieChartCategory;
        public RecyclerView rvCategoryList;
        
        public AdditionalViewHolder(@NonNull View itemView) {
            super(itemView);
            llAdditionalContainer = itemView.findViewById(R.id.ll_additional_container);
            tvAdditionalTitle = itemView.findViewById(R.id.tv_additional_title);
            tvFilterAllPie = itemView.findViewById(R.id.tv_filter_all_pie);
            tvFilterSelfPie = itemView.findViewById(R.id.tv_filter_self_pie);
            tvFilterPartnerPie = itemView.findViewById(R.id.tv_filter_partner_pie);
            tvFilterSharedPie = itemView.findViewById(R.id.tv_filter_shared_pie);
            pieChartCategory = itemView.findViewById(R.id.pie_chart_category);
            rvCategoryList = itemView.findViewById(R.id.rv_category_list);
        }
    }
}