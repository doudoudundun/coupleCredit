package com.example.couplecredit.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.fragment.InventoryFragment;
import com.example.couplecredit.fragment.InventoryFragment.InventoryItem;

import java.util.List;

/**
 * 存货列表适配器
 */
public class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.ViewHolder> {

    private List<InventoryItem> items;
    private InventoryFragment fragment;

    public InventoryAdapter(List<InventoryItem> items, InventoryFragment fragment) {
        this.items = items;
        this.fragment = fragment;
    }

    public void updateData(List<InventoryItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inventory, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InventoryItem item = items.get(position);

        // 设置名称
        holder.tvName.setText(item.name);

        // 设置类别标签
        holder.tvCategory.setText(item.category);

        // 设置存量信息
        holder.tvQuantity.setText(String.format("存量: %.1f", item.quantity));
        holder.tvUnit.setText(item.unit);

        // 设置告急标识
        if (item.isLowStock()) {
            holder.tvLowStockBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tvLowStockBadge.setVisibility(View.GONE);
        }

        // 设置最近消耗时间
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) {
            holder.tvLastConsumed.setText("最近消耗: " + formatDate(item.lastConsumedAt));
        } else if (item.updatedAt != null) {
            holder.tvLastConsumed.setText("更新: " + formatDate(item.updatedAt));
        } else {
            holder.tvLastConsumed.setText("最近消耗: 无记录");
        }

        // 设置备注
        if (item.note != null && !item.note.isEmpty()) {
            holder.tvNote.setVisibility(View.VISIBLE);
            holder.tvNote.setText(item.note);
        } else {
            holder.tvNote.setVisibility(View.GONE);
        }

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

        // 设置消耗按钮
        holder.btnConsume.setOnClickListener(v -> {
            fragment.showConsumeDialog(item);
        });

        // 设置补货按钮
        holder.btnReplenish.setOnClickListener(v -> {
            fragment.showReplenishDialog(item);
        });
    }

    private String formatDate(String dateStr) {
        // 简化日期显示
        if (dateStr == null) return "";
        if (dateStr.contains(" ")) {
            String[] parts = dateStr.split(" ");
            if (parts.length >= 2) {
                // 只显示日期和小时
                return parts[0] + " " + parts[1].substring(0, Math.min(5, parts[1].length()));
            }
        }
        return dateStr;
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvName;
        TextView tvCategory;
        TextView tvQuantity;
        TextView tvUnit;
        TextView tvLowStockBadge;
        TextView tvLastConsumed;
        TextView tvNote;
        ImageButton btnConsume;
        ImageButton btnReplenish;

        ViewHolder(View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.iv_inventory_image);
            tvName = itemView.findViewById(R.id.tv_inventory_name);
            tvCategory = itemView.findViewById(R.id.tv_inventory_category);
            tvQuantity = itemView.findViewById(R.id.tv_quantity);
            tvUnit = itemView.findViewById(R.id.tv_unit);
            tvLowStockBadge = itemView.findViewById(R.id.tv_low_stock_badge);
            tvLastConsumed = itemView.findViewById(R.id.tv_last_consumed);
            tvNote = itemView.findViewById(R.id.tv_note);
            btnConsume = itemView.findViewById(R.id.btn_consume);
            btnReplenish = itemView.findViewById(R.id.btn_replenish);
        }
    }
}