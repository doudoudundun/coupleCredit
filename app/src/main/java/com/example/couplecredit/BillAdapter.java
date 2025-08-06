package com.example.couplecredit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class BillAdapter extends RecyclerView.Adapter<BillAdapter.BillViewHolder> {
    
    private List<BillBean> billItems;
    private Context context;
    private AdapterView.OnItemClickListener mListener;
    
    public BillAdapter(Context context, List<BillBean> billItems, AdapterView.OnItemClickListener listener) {
        this.context = context;
        this.billItems = billItems;
        this.mListener = listener;
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
        holder.tvAmount.setText(String.valueOf(item.getFare()));
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