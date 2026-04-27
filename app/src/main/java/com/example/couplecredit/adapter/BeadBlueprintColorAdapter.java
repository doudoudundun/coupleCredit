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
import com.example.couplecredit.fragment.BeadBlueprintDetailFragment;
import com.example.couplecredit.utils.BeadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BeadBlueprintColorAdapter extends RecyclerView.Adapter<BeadBlueprintColorAdapter.ViewHolder> {

    private final List<BeadBlueprintDetailFragment.BlueprintColorDisplayItem> items = new ArrayList<>();

    public void submitList(List<BeadBlueprintDetailFragment.BlueprintColorDisplayItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bead_blueprint_color, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeadBlueprintDetailFragment.BlueprintColorDisplayItem item = items.get(position);
        holder.tvCode.setText(item.colorCode);
        holder.tvQuantity.setText(String.format(Locale.getDefault(), "每次 %d 颗", item.quantityPerBuild));
        holder.tvTotal.setText(String.format(Locale.getDefault(), "累计 %d 颗", item.totalConsumed));
        holder.tvTransparent.setVisibility(item.isTransparent ? View.VISIBLE : View.GONE);

        GradientDrawable swatch = (GradientDrawable) holder.viewSwatch.getBackground();
        if (swatch == null) {
            swatch = new GradientDrawable();
            swatch.setShape(GradientDrawable.OVAL);
            swatch.setStroke(1, Color.parseColor("#D1D5DB"));
            holder.viewSwatch.setBackground(swatch);
        }
        swatch.setColor(BeadUtils.parseColorSafely(item.hexColor));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View viewSwatch;
        TextView tvCode;
        TextView tvQuantity;
        TextView tvTotal;
        TextView tvTransparent;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            viewSwatch = itemView.findViewById(R.id.view_blueprint_color_swatch);
            tvCode = itemView.findViewById(R.id.tv_blueprint_color_code);
            tvQuantity = itemView.findViewById(R.id.tv_blueprint_color_quantity);
            tvTotal = itemView.findViewById(R.id.tv_blueprint_color_total);
            tvTransparent = itemView.findViewById(R.id.tv_blueprint_color_transparent);
        }
    }
}
