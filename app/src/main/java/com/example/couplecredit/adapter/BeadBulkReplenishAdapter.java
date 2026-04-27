package com.example.couplecredit.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BeadBulkReplenishAdapter extends RecyclerView.Adapter<BeadBulkReplenishAdapter.ViewHolder> {

    private final List<BeadInventoryViewModel.BeadInventoryItem> items = new ArrayList<>();
    private final Set<String> selectedCodes = new HashSet<>();
    private Runnable onSelectionChanged;

    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }

    public void submitList(List<BeadInventoryViewModel.BeadInventoryItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selectedCodes.size();
    }

    public List<String> getSelectedCodesInDisplayOrder() {
        List<String> orderedCodes = new ArrayList<>();
        for (BeadInventoryViewModel.BeadInventoryItem item : items) {
            if (selectedCodes.contains(item.colorCode)) {
                orderedCodes.add(item.colorCode);
            }
        }
        for (String code : selectedCodes) {
            if (!orderedCodes.contains(code)) {
                orderedCodes.add(code);
            }
        }
        return orderedCodes;
    }

    public void clearSelections() {
        selectedCodes.clear();
        notifyDataSetChanged();
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bulk_replenish_color, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeadInventoryViewModel.BeadInventoryItem item = items.get(position);
        holder.tvCode.setText(item.colorCode);

        GradientDrawable swatch = (GradientDrawable) holder.viewSwatch.getBackground();
        if (swatch == null) {
            swatch = new GradientDrawable();
            swatch.setShape(GradientDrawable.OVAL);
            swatch.setStroke(1, Color.parseColor("#D1D5DB"));
            holder.viewSwatch.setBackground(swatch);
        }
        swatch.setColor(BeadUtils.parseColorSafely(item.hexColor));

        holder.cb.setOnCheckedChangeListener(null);
        holder.cb.setChecked(selectedCodes.contains(item.colorCode));

        holder.cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedCodes.add(item.colorCode);
            } else {
                selectedCodes.remove(item.colorCode);
            }
            if (onSelectionChanged != null) {
                onSelectionChanged.run();
            }
        });

        holder.itemView.setOnClickListener(v -> {
            holder.cb.setChecked(!holder.cb.isChecked());
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox cb;
        View viewSwatch;
        TextView tvCode;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cb = itemView.findViewById(R.id.cb_bulk_color);
            viewSwatch = itemView.findViewById(R.id.view_bulk_swatch);
            tvCode = itemView.findViewById(R.id.tv_bulk_color_code);
        }
    }
}
