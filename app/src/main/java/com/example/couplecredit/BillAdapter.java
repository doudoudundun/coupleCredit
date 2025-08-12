package com.example.couplecredit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;

public class BillAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int TYPE_DATE_HEADER = 0;
    private static final int TYPE_BILL_ITEM = 1;
    
    private List<Object> items; // 混合数据：Map<String, List<BillBean>>(日期组) 
    private Context context;
    private AdapterView.OnItemClickListener mListener;
    
    public BillAdapter(Context context, List<Object> items, AdapterView.OnItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.mListener = listener;
    }
    
    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof Map) {
            return TYPE_DATE_HEADER;
        } else if (item instanceof BillBean) {
            return TYPE_BILL_ITEM;
        }
        return TYPE_BILL_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_DATE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_date_header, parent, false);
            return new DateHeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_chat_bill, parent, false);
            return new BillViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DateHeaderViewHolder) {
            DateHeaderViewHolder dateHolder = (DateHeaderViewHolder) holder;
            Map<String, List<BillBean>> dateGroup = (Map<String, List<BillBean>>) items.get(position);
            
            // 获取日期和对应的账单列表
            Map.Entry<String, List<BillBean>> entry = dateGroup.entrySet().iterator().next();
            String date = entry.getKey();
            List<BillBean> bills = entry.getValue();
            
            // 设置日期
            dateHolder.tvDate.setText(date);
            
            // 清空之前的账单项
            dateHolder.llBillsContainer.removeAllViews();
            
            // 动态添加账单项
            for (BillBean bill : bills) {
                View billView = LayoutInflater.from(context).inflate(R.layout.item_bill, dateHolder.llBillsContainer, false);
                
                TextView tvKind = billView.findViewById(R.id.tv_category_name);
                TextView tvMoney = billView.findViewById(R.id.tv_amount);
                TextView tvRemark = billView.findViewById(R.id.tv_category_desc);
                
                tvKind.setText(bill.getCategoryName());
                tvMoney.setText(String.format("%.2f", bill.getFare()));
                tvRemark.setText(bill.getCategoryDesc());
                
                // 设置颜色（根据收入支出类型判断）
                if (bill.getIncomeType() == 0) { // 支出
                    tvMoney.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                    tvMoney.setText("-" + String.format("%.2f", bill.getFare()));
                } else { // 收入
                    tvMoney.setTextColor(context.getResources().getColor(android.R.color.holo_green_dark));
                    tvMoney.setText("+" + String.format("%.2f", bill.getFare()));
                }
                
                dateHolder.llBillsContainer.addView(billView);
            }
        } else if (holder instanceof BillViewHolder) {
            // 聊天模式下的单个账单项
            BillViewHolder billHolder = (BillViewHolder) holder;
            BillBean bill = (BillBean) items.get(position);
            
            billHolder.tvKind.setText(bill.getCategoryName());
            billHolder.tvMoney.setText(String.format("%.2f", bill.getFare()));
            billHolder.tvRemark.setText(bill.getCategoryDesc());
            
            // 设置时间显示（格式：MM.dd，由于BillBean没有具体时间，显示日期）
            String timeStr = String.format("%02d.%02d", bill.getMonth(), bill.getDay());
            billHolder.tvTime.setText(timeStr);
            
            // 设置颜色（根据用户ID判断支出/收入）
            if (bill.getUserId() == 1 || bill.getUserId() == 2) {
                billHolder.tvMoney.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                billHolder.tvMoney.setText("-" + String.format("%.2f", bill.getFare()));
            } else {
                billHolder.tvMoney.setTextColor(context.getResources().getColor(android.R.color.holo_green_dark));
                billHolder.tvMoney.setText("+" + String.format("%.2f", bill.getFare()));
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
    
    static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate;
        LinearLayout llBillsContainer;
        
        public DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tv_date_header);
            llBillsContainer = itemView.findViewById(R.id.ll_bills_container);
        }
    }
    
    // 账单项ViewHolder（用于聊天模式）
    static class BillViewHolder extends RecyclerView.ViewHolder {
        TextView tvKind;
        TextView tvMoney;
        TextView tvRemark;
        TextView tvTime;
        
        public BillViewHolder(@NonNull View itemView) {
            super(itemView);
            tvKind = itemView.findViewById(R.id.tv_kind);
            tvMoney = itemView.findViewById(R.id.tv_money);
            tvRemark = itemView.findViewById(R.id.tv_remark);
            tvTime = itemView.findViewById(R.id.tv_time);
        }
    }
}