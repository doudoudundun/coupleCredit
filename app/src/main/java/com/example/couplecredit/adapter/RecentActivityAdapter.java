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
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_activity, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InventoryItem item = items.get(position);
        holder.tvName.setText(item.name);
        holder.tvChange.setText(resolveChangeType(item));
        holder.tvQuantity.setText(String.format("当前 %.1f %s", item.quantity, item.unit));

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

    private String resolveChangeType(InventoryItem item) {
        if (item.lastActionLabel != null && !item.lastActionLabel.isEmpty()) {
            return item.lastActionLabel;
        }
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) {
            return "消耗";
        }
        return "新增";
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
