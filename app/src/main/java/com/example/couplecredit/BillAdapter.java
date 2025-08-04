package com.example.couplecredit;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class BillAdapter extends RecyclerView.Adapter<BillAdapter.BillViewHolder> {
    
    private List<BillBean> billItems;
    
    public BillAdapter(List<BillBean> billItems) {
        this.billItems = billItems;
    }
    
    @NonNull
    @Override
    public BillViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bill, parent, false);
        return new BillViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull BillViewHolder holder, int position) {
        BillBean item = billItems.get(position);
        holder.tvCategoryName.setText(item.getCategoryName());
        holder.tvCategoryDesc.setText(item.getCategoryDesc());
        holder.tvAmount.setText(item.getFare());
        holder.ivCategoryIcon.setImageResource(item.getIconResId());
    }
    
    @Override
    public int getItemCount() {
        return billItems.size();
    }
    
    static class BillViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCategoryIcon;
        TextView tvCategoryName;
        TextView tvCategoryDesc;
        TextView tvAmount;
        TextView tvDate;
        
        public BillViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCategoryIcon = itemView.findViewById(R.id.iv_category_icon);
            tvCategoryName = itemView.findViewById(R.id.tv_category_name);
            tvCategoryDesc = itemView.findViewById(R.id.tv_category_desc);
            tvAmount = itemView.findViewById(R.id.tv_amount);
            tvDate = itemView.findViewById(R.id.tv_date);
        }
    }
}