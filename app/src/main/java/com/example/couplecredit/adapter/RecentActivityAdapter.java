package com.example.couplecredit.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.fragment.InventoryFragment.InventoryItem;

import java.util.Locale;
import java.util.List;

public class RecentActivityAdapter extends RecyclerView.Adapter<RecentActivityAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(InventoryItem item);
    }

    private final Context context;
    private List<InventoryItem> items;
    private OnItemClickListener clickListener;

    public RecentActivityAdapter(Context context, List<InventoryItem> items) {
        this.context = context;
        this.items = items;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
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

        String actionLabel = resolveChangeType(item);
        holder.tvChange.setText(actionLabel);

        String quantityText = formatQuantity(item.quantity) + " " + item.unit;
        String detail = item.category + " · " + quantityText;
        holder.tvQuantity.setText(detail);

        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            Glide.with(holder.ivImage.getContext())
                    .load(ApiConfigManager.resolveResourceUrl(context, item.imageUrl))
                    .placeholder(R.drawable.ic_inventory_placeholder)
                    .error(R.drawable.ic_inventory_placeholder)
                    .centerCrop()
                    .into(holder.ivImage);
        } else {
            holder.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onItemClick(item);
        });
    }

    private String formatQuantity(double value) {
        if (value == (long) value) {
            return String.format(Locale.getDefault(), "%d", (long) value);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
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
