package com.example.couplecredit.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class BeadColorAdapter extends RecyclerView.Adapter<BeadColorAdapter.ViewHolder> {

    public interface OnBeadColorClickListener {
        void onBeadColorClick(BeadColorDisplayItem item);
    }

    public static class BeadColorDisplayItem {
        public String colorCode;
        public String hexColor;
        public int quantity;
        public Integer thresholdOverride;
        public int defaultThreshold;
        public int totalConsumed;
        public String colorGroup;
        public boolean isTransparent;
        public boolean isLowStock;

        public int getEffectiveThreshold() {
            return thresholdOverride != null ? thresholdOverride : defaultThreshold;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BeadColorDisplayItem)) return false;
            BeadColorDisplayItem that = (BeadColorDisplayItem) o;
            return quantity == that.quantity && isLowStock == that.isLowStock
                    && Objects.equals(colorCode, that.colorCode) && Objects.equals(hexColor, that.hexColor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(colorCode, hexColor, quantity, isLowStock);
        }
    }

    private final List<BeadColorDisplayItem> items = new ArrayList<>();
    private OnBeadColorClickListener listener;

    public void setOnBeadColorClickListener(OnBeadColorClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<BeadColorDisplayItem> newItems) {
        if (newItems == null) newItems = new ArrayList<>();
        List<BeadColorDisplayItem> finalNewItems = newItems;
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return items.size(); }
            @Override public int getNewListSize() { return finalNewItems.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return Objects.equals(items.get(oldPos).colorCode, finalNewItems.get(newPos).colorCode);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return items.get(oldPos).equals(finalNewItems.get(newPos));
            }
        });
        items.clear();
        items.addAll(newItems);
        result.dispatchUpdatesTo(this);
    }

    public static List<BeadColorDisplayItem> buildDisplayItems(List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems,
                                                                String query,
                                                                boolean lowStockOnly) {
        return buildDisplayItems(inventoryItems, query, lowStockOnly, null);
    }

    public static List<BeadColorDisplayItem> buildDisplayItems(List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems,
                                                                String query,
                                                                boolean lowStockOnly,
                                                                String colorGroup) {
        String normalizedQuery = BeadUtils.normalizeColorCode(query);
        if (normalizedQuery == null) {
            normalizedQuery = "";
        }
        List<BeadColorDisplayItem> result = new ArrayList<>();
        if (inventoryItems == null) {
            return result;
        }

        for (BeadInventoryViewModel.BeadInventoryItem source : inventoryItems) {
            if (source == null || source.colorCode == null || source.colorCode.isEmpty()) {
                continue;
            }
            if (colorGroup != null && !colorGroup.equals(source.colorGroup)) {
                continue;
            }
            if (!normalizedQuery.isEmpty() && !source.colorCode.toUpperCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }
            if (lowStockOnly && !source.isLowStock()) {
                continue;
            }
            BeadColorDisplayItem item = new BeadColorDisplayItem();
            item.colorCode = source.colorCode.toUpperCase(Locale.ROOT);
            item.hexColor = source.hexColor;
            item.colorGroup = source.colorGroup;
            item.quantity = source.quantity;
            item.thresholdOverride = source.thresholdOverride;
            item.defaultThreshold = source.defaultThreshold;
            item.totalConsumed = source.totalConsumed;
            item.isTransparent = source.isTransparent;
            item.isLowStock = source.isLowStock();
            result.add(item);
        }
        return result;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bead_color, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeadColorDisplayItem item = items.get(position);
        holder.tvCode.setText(item.colorCode);
        holder.tvQuantity.setText(String.valueOf(item.quantity));
        holder.tvLowStock.setVisibility(item.isLowStock ? View.VISIBLE : View.GONE);

        GradientDrawable swatch = (GradientDrawable) holder.viewSwatch.getBackground();
        if (swatch == null) {
            swatch = new GradientDrawable();
            swatch.setShape(GradientDrawable.OVAL);
            swatch.setStroke(1, Color.parseColor("#D1D5DB"));
            holder.viewSwatch.setBackground(swatch);
        }
        swatch.setColor(BeadUtils.parseColorSafely(item.hexColor));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onBeadColorClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View viewSwatch;
        TextView tvCode;
        TextView tvQuantity;
        TextView tvLowStock;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            viewSwatch = itemView.findViewById(R.id.view_bead_swatch);
            tvCode = itemView.findViewById(R.id.tv_bead_color_code);
            tvQuantity = itemView.findViewById(R.id.tv_bead_quantity);
            tvLowStock = itemView.findViewById(R.id.tv_bead_low_stock);
        }
    }
}
