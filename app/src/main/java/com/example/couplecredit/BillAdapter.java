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
        if (items.get(position) instanceof Map) {
            return TYPE_DATE_HEADER;
        } else {
            return TYPE_BILL_ITEM;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_DATE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_date_header, parent, false);
            return new DateHeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bill, parent, false);
            return new BillViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DateHeaderViewHolder) {
            Map<String, List<BillBean>> dateGroup = (Map<String, List<BillBean>>) items.get(position);
            String date = dateGroup.keySet().iterator().next();
            List<BillBean> bills = dateGroup.get(date);
            
            DateHeaderViewHolder dateHolder = (DateHeaderViewHolder) holder;
            dateHolder.tvDateHeader.setText(date);
            
            // 清空之前的账单项
            dateHolder.llBillsContainer.removeAllViews();
            
            // 动态添加账单项
            for (BillBean bill : bills) {
                View billItemView = LayoutInflater.from(context)
                    .inflate(R.layout.item_bill, dateHolder.llBillsContainer, false);
                
                ImageView ivIcon = billItemView.findViewById(R.id.iv_category_icon);
                TextView tvName = billItemView.findViewById(R.id.tv_category_name);
                TextView tvDesc = billItemView.findViewById(R.id.tv_category_desc);
                TextView tvAmount = billItemView.findViewById(R.id.tv_amount);
                
                ivIcon.setImageResource(bill.getIconResId());
                tvName.setText(bill.getCategoryName());
                tvDesc.setText(bill.getCategoryDesc());
                tvAmount.setText(String.valueOf(bill.getFare()));
                
                dateHolder.llBillsContainer.addView(billItemView);
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
    
    static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvDateHeader;
        LinearLayout llBillsContainer;
        
        public DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDateHeader = itemView.findViewById(R.id.tv_date_header);
            llBillsContainer = itemView.findViewById(R.id.ll_bills_container);
        }
    }
    
    static class BillViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCategoryIcon;
        TextView tvCategoryName;
        TextView tvCategoryDesc;
        TextView tvAmount;
        
        public BillViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCategoryIcon = itemView.findViewById(R.id.iv_category_icon);
            tvCategoryName = itemView.findViewById(R.id.tv_category_name);
            tvCategoryDesc = itemView.findViewById(R.id.tv_category_desc);
            tvAmount = itemView.findViewById(R.id.tv_amount);
        }
    }
}