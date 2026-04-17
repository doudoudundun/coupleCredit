package com.example.couplecredit.adapter;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
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

    private List<InventoryItem> items;
    private final InventoryActionListener actionListener;

    public InventoryAdapter(List<InventoryItem> items, InventoryActionListener actionListener) {
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
                    .load(item.imageUrl)
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
        bg.setCornerRadius(dp * 16);
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
            row.setTextSize(15);
            row.setPadding(dp * 16, dp * 12, dp * 16, dp * 12);
            row.setGravity(android.view.Gravity.CENTER);
            int fi = i;
            row.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
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

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.CustomDialogStyle);
        builder.setView(container);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialogRef[0] = dialog;
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dp * 140, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private final AlertDialog[] dialogRef = new AlertDialog[1];

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
