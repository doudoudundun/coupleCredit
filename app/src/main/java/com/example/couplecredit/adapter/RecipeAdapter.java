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
import com.example.couplecredit.api.AuthApiModels;

import java.util.List;

public class RecipeAdapter extends RecyclerView.Adapter<RecipeAdapter.ViewHolder> {

    public interface RecipeActionListener {
        void onEdit(AuthApiModels.RecipeItemData item);
        void onDelete(AuthApiModels.RecipeItemData item);
        void onCook(AuthApiModels.RecipeItemData item);
    }

    private List<AuthApiModels.RecipeItemData> items;
    private final RecipeActionListener listener;

    public RecipeAdapter(List<AuthApiModels.RecipeItemData> items, RecipeActionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void updateData(List<AuthApiModels.RecipeItemData> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recipe, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.RecipeItemData item = items.get(position);
        holder.tvTitle.setText(item.title);
        holder.tvDesc.setText(item.description != null ? item.description : "");
        holder.tvIngredients.setText(item.ingredientCount + " 种食材");

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

        holder.btnMore.setOnClickListener(v -> showMoreMenu(v, item));
    }

    private void showMoreMenu(View anchor, AuthApiModels.RecipeItemData item) {
        Context ctx = anchor.getContext();
        int dp = (int) (ctx.getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp * 4, dp * 8, dp * 4, dp * 8);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp * 12);
        bg.setColor(Color.WHITE);
        container.setBackground(bg);

        String[] labels = {"烹饪", "编辑", "删除"};
        int[] colors = {Color.parseColor("#07C160"), Color.parseColor("#333333"), Color.parseColor("#E53935")};
        Runnable[] actions = {
                () -> { if (listener != null) listener.onCook(item); },
                () -> { if (listener != null) listener.onEdit(item); },
                () -> { if (listener != null) listener.onDelete(item); }
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

    @Override
    public int getItemCount() { return items != null ? items.size() : 0; }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvTitle;
        TextView tvDesc;
        TextView tvIngredients;
        ImageButton btnMore;

        ViewHolder(View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.iv_recipe_image);
            tvTitle = itemView.findViewById(R.id.tv_recipe_title);
            tvDesc = itemView.findViewById(R.id.tv_recipe_desc);
            tvIngredients = itemView.findViewById(R.id.tv_recipe_ingredients);
            btnMore = itemView.findViewById(R.id.btn_recipe_more);
        }
    }
}
