package com.example.couplecredit.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BeadBulkReplenishAdapter extends RecyclerView.Adapter<BeadBulkReplenishAdapter.ViewHolder> {

    public static class ReplenishEntry {
        public final String colorCode;
        public final int amount;

        public ReplenishEntry(String colorCode, int amount) {
            this.colorCode = colorCode;
            this.amount = amount;
        }
    }

    private final List<BeadInventoryViewModel.BeadInventoryItem> items = new ArrayList<>();
    private final Set<String> selectedCodes = new HashSet<>();
    private final Map<String, Integer> quantities = new HashMap<>();
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

    public List<ReplenishEntry> getSelectedItems() {
        List<ReplenishEntry> entries = new ArrayList<>();
        for (String code : selectedCodes) {
            Integer qty = quantities.get(code);
            if (qty != null && qty > 0) {
                entries.add(new ReplenishEntry(code, qty));
            }
        }
        return entries;
    }

    public void clearSelections() {
        selectedCodes.clear();
        quantities.clear();
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

        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.OVAL);
        swatch.setColor(BeadUtils.parseColorSafely(item.hexColor));
        swatch.setStroke(1, Color.parseColor("#D1D5DB"));
        holder.viewSwatch.setBackground(swatch);

        boolean selected = selectedCodes.contains(item.colorCode);
        holder.cb.setOnCheckedChangeListener(null);
        holder.cb.setChecked(selected);
        holder.etQuantity.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (selected && quantities.containsKey(item.colorCode)) {
            holder.etQuantity.setText(String.valueOf(quantities.get(item.colorCode)));
        } else {
            holder.etQuantity.setText("");
        }

        holder.cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedCodes.add(item.colorCode);
                holder.etQuantity.setVisibility(View.VISIBLE);
            } else {
                selectedCodes.remove(item.colorCode);
                quantities.remove(item.colorCode);
                holder.etQuantity.setText("");
                holder.etQuantity.setVisibility(View.GONE);
            }
            if (onSelectionChanged != null) {
                onSelectionChanged.run();
            }
        });

        holder.etQuantity.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    quantities.put(item.colorCode, Integer.parseInt(s.toString()));
                } catch (NumberFormatException e) {
                    quantities.remove(item.colorCode);
                }
            }
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
        EditText etQuantity;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cb = itemView.findViewById(R.id.cb_bulk_color);
            viewSwatch = itemView.findViewById(R.id.view_bulk_swatch);
            tvCode = itemView.findViewById(R.id.tv_bulk_color_code);
            etQuantity = itemView.findViewById(R.id.et_bulk_quantity);
        }
    }
}
