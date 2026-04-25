package com.example.couplecredit.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    }

    private final List<BeadColorDisplayItem> items = new ArrayList<>();
    private OnBeadColorClickListener listener;

    public void setOnBeadColorClickListener(OnBeadColorClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<BeadColorDisplayItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
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
            BeadColorDisplayItem item = new BeadColorDisplayItem();
            item.colorCode = source.colorCode.trim().toUpperCase(Locale.ROOT);
            item.hexColor = source.hexColor;
            item.colorGroup = source.colorGroup;
            item.quantity = source.quantity;
            item.thresholdOverride = source.thresholdOverride;
            item.defaultThreshold = source.defaultThreshold;
            item.totalConsumed = source.totalConsumed;
            item.isTransparent = source.isTransparent;
            item.isLowStock = source.isLowStock();

            if (!normalizedQuery.isEmpty() && !item.colorCode.contains(normalizedQuery)) {
                continue;
            }
            if (lowStockOnly && !item.isLowStock) {
                continue;
            }
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

        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.OVAL);
        swatch.setColor(BeadUtils.parseColorSafely(item.hexColor));
        swatch.setStroke(1, Color.parseColor("#D1D5DB"));
        holder.viewSwatch.setBackground(swatch);

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
        TextView tvThreshold;
        TextView tvLowStock;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            viewSwatch = itemView.findViewById(R.id.view_bead_swatch);
            tvCode = itemView.findViewById(R.id.tv_bead_color_code);
            tvQuantity = itemView.findViewById(R.id.tv_bead_quantity);
            tvThreshold = itemView.findViewById(R.id.tv_bead_threshold);
            tvLowStock = itemView.findViewById(R.id.tv_bead_low_stock);
        }
    }
}
