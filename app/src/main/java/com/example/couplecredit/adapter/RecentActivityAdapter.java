package com.example.couplecredit.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.fragment.InventoryFragment.InventoryItem;

import java.util.List;

/**
 * 最近活动适配器（横向滚动）
 */
public class RecentActivityAdapter extends RecyclerView.Adapter<RecentActivityAdapter.ViewHolder> {

    private List<InventoryItem> items;

    public RecentActivityAdapter(List<InventoryItem> items) {
        this.items = items;
    }

    public void updateData(List<InventoryItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_activity, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InventoryItem item = items.get(position);

        // 设置名称
        holder.tvName.setText(item.name);

        // 设置变化信息
        String changeInfo;
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) {
            changeInfo = "消耗记录";
        } else {
            changeInfo = "新增";
        }
        holder.tvChange.setText(changeInfo);

        // 设置存量
        holder.tvQuantity.setText(String.format("%.1f %s", item.quantity, item.unit));

        // 设置图片
        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            Glide.with(holder.ivImage.getContext())
                    .load(item.imageUrl)
                    .placeholder(R.drawable.ic_inventory_placeholder)
                    .error(R.drawable.ic_inventory_placeholder)
                    .centerCrop()
                    .into(holder.ivImage);
        } else {
            holder.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
        }
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvName;
        TextView tvChange;
        TextView tvQuantity;

        ViewHolder(View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.iv_recent_image);
            tvName = itemView.findViewById(R.id.tv_recent_name);
            tvChange = itemView.findViewById(R.id.tv_recent_change);
            tvQuantity = itemView.findViewById(R.id.tv_recent_quantity);
        }
    }
}