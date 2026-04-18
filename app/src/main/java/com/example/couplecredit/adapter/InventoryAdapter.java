package com.example.couplecredit.adapter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.fragment.InventoryFragment;
import com.example.couplecredit.fragment.InventoryFragment.InventoryItem;

import java.util.List;

public class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.ViewHolder> {

    public interface InventoryActionListener {
        void onConsume(InventoryItem item);
        void onReplenish(InventoryItem item);
        void onEdit(InventoryItem item);
        void onDelete(InventoryItem item);
    }

    private final Context context;
    private List<InventoryItem> items;
    private final InventoryActionListener actionListener;

    public InventoryAdapter(Context context, List<InventoryItem> items, InventoryActionListener actionListener) {
        this.context = context;
        this.items = items;
        this.actionListener = actionListener;
    }

    public void updateData(List<InventoryItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_inventory, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InventoryItem item = items.get(position);
        holder.tvName.setText(item.name);
        holder.tvCategory.setText(item.category);
        holder.tvQuantity.setText(String.format("当前 %.1f %s", item.quantity, item.unit));
        holder.tvLastConsumed.setText(buildStatusText(item));

        if (item.isLowStock()) {
            holder.tvLowStockBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tvLowStockBadge.setVisibility(View.GONE);
        }

        if (item.note != null && !item.note.trim().isEmpty()) {
            holder.tvNote.setVisibility(View.VISIBLE);
            holder.tvNote.setText(item.note);
        } else {
            holder.tvNote.setVisibility(View.GONE);
        }

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

        holder.btnConsume.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onConsume(item);
            }
        });

        holder.btnReplenish.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onReplenish(item);
            }
        });

        holder.btnMore.setOnClickListener(v -> showMoreMenu(v, item));
    }

    private String buildStatusText(InventoryItem item) {
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) {
            return "最近消耗: " + formatDate(item.lastConsumedAt);
        }
        if (item.updatedAt != null && !item.updatedAt.isEmpty()) {
            return "最近更新: " + formatDate(item.updatedAt);
        }
        return "最近更新: 暂无记录";
    }

    private void showMoreMenu(View anchor, InventoryItem item) {
        Context ctx = anchor.getContext();
        int dp = (int) (ctx.getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp * 4, dp * 8, dp * 4, dp * 8);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp * 12);
        bg.setColor(Color.WHITE);
        container.setBackground(bg);

        String[] labels = {"编辑", "删除"};
        int[] colors = {Color.parseColor("#333333"), Color.parseColor("#E53935")};
        Runnable[] actions = {
                () -> { if (actionListener != null) actionListener.onEdit(item); },
                () -> { if (actionListener != null) actionListener.onDelete(item); }
        };

        for (int i = 0; i < labels.length; i++) {
            TextView row = new TextView(ctx);
            row.setText(labels[i]);
            row.setTextColor(colors[i]);
            row.setTextSize(14);
            row.setPadding(dp * 16, dp * 10, dp * 16, dp * 10);
            row.setGravity(android.view.Gravity.CENTER);
            int fi = i;
            row.setOnClickListener(v -> {
                actions[fi].run();
            });
            container.addView(row);
            if (i < labels.length - 1) {
                View divider = new View(ctx);
                divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
                container.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp)));
            }
        }

        int popupWidth = dp * 120;
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = container.getMeasuredHeight();

        PopupWindow popup = new PopupWindow(container, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp * 6);

        int offsetX = anchor.getWidth() - popupWidth + dp * 4;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int screenHeight = ctx.getResources().getDisplayMetrics().heightPixels;
        boolean showAbove = location[1] + anchor.getHeight() + popupHeight > screenHeight;

        if (showAbove) {
            popup.showAsDropDown(anchor, offsetX, -(anchor.getHeight() + popupHeight));
        } else {
            popup.showAsDropDown(anchor, offsetX, 0);
        }
    }

    private String formatDate(String dateStr) {
        if (dateStr == null) {
            return "";
        }
        String normalized = dateStr.replace('T', ' ');
        if (normalized.contains(".")) {
            normalized = normalized.substring(0, normalized.indexOf('.'));
        }
        if (normalized.length() >= 16) {
            return normalized.substring(0, 16);
        }
        return normalized;
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
        TextView tvLowStockBadge;
        TextView tvLastConsumed;
        TextView tvNote;
        TextView btnConsume;
        TextView btnReplenish;
        ImageButton btnMore;

        ViewHolder(View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.iv_inventory_image);
            tvName = itemView.findViewById(R.id.tv_inventory_name);
            tvCategory = itemView.findViewById(R.id.tv_inventory_category);
            tvQuantity = itemView.findViewById(R.id.tv_quantity);
            tvLowStockBadge = itemView.findViewById(R.id.tv_low_stock_badge);
            tvLastConsumed = itemView.findViewById(R.id.tv_last_consumed);
            tvNote = itemView.findViewById(R.id.tv_note);
            btnConsume = itemView.findViewById(R.id.btn_consume);
            btnReplenish = itemView.findViewById(R.id.btn_replenish);
            btnMore = itemView.findViewById(R.id.btn_more);
        }
    }
}
