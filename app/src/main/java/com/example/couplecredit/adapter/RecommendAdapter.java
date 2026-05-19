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
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.CalorieFormatUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class RecommendAdapter extends RecyclerView.Adapter<RecommendAdapter.ViewHolder> {

    private final List<AuthApiModels.RecommendRecipeItem> items = new ArrayList<>();
    private final OnRecipeClickListener listener;
    private static final Random random = new Random();
    private final Map<Integer, Integer> placeholderHeights = new HashMap<>();

    public interface OnRecipeClickListener {
        void onRecipeClick(int recipeId);
        void onAddToCart(AuthApiModels.RecommendRecipeItem item);
    }

    public RecommendAdapter(OnRecipeClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<AuthApiModels.RecommendRecipeItem> newItems) {
        items.clear();
        items.addAll(newItems);
        placeholderHeights.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recipe_recommend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.RecommendRecipeItem item = items.get(position);

        holder.tvTitle.setText(item.title);
        holder.tvCalories.setText(CalorieFormatUtils.formatCalories(item.totalCalories, item.calorieSource));
        holder.tvCalories.setTextColor(CalorieFormatUtils.resolveCalorieColor(item.totalCalories, item.calorieSource));

        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            String url = ApiConfigManager.resolveResourceUrl(holder.ivImage.getContext(), item.imageUrl);
            Glide.with(holder.ivImage.getContext())
                    .load(url)
                    .centerCrop()
                    .placeholder(R.drawable.ic_inventory_placeholder)
                    .error(R.drawable.ic_inventory_placeholder)
                    .into(holder.ivImage);
        } else {
            int height = placeholderHeights.containsKey(item.recipeId)
                    ? placeholderHeights.get(item.recipeId)
                    : 120 + random.nextInt(80);
            placeholderHeights.put(item.recipeId, height);
            ViewGroup.LayoutParams lp = holder.ivImage.getLayoutParams();
            lp.height = (int) (height * holder.ivImage.getContext().getResources().getDisplayMetrics().density);
            holder.ivImage.setLayoutParams(lp);
            holder.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
        }

        AuthApiModels.MatchInfo match = item.matchInfo;
        if (match != null) {
            int missing = match.missingIngredients != null ? match.missingIngredients.size() : 0;
            if (missing == 0) {
                holder.tvMatch.setText("食材齐全");
                holder.tvMatch.setTextColor(0xFF2E7D32);
                holder.tvMatch.setBackgroundResource(R.drawable.bg_match_full);
            } else {
                holder.tvMatch.setText("缺" + missing + "样");
                if (missing <= 1) {
                    holder.tvMatch.setTextColor(0xFFE65100);
                    holder.tvMatch.setBackgroundResource(R.drawable.bg_match_partial);
                } else {
                    holder.tvMatch.setTextColor(0xFFC62828);
                    holder.tvMatch.setBackgroundResource(R.drawable.bg_match_missing);
                }
            }

            StringBuilder preview = new StringBuilder();
            if (match.matchedIngredients != null) {
                for (int i = 0; i < Math.min(match.matchedIngredients.size(), 3); i++) {
                    if (preview.length() > 0) preview.append("、");
                    preview.append(match.matchedIngredients.get(i));
                }
            }
            if (match.missingIngredients != null && !match.missingIngredients.isEmpty()) {
                for (int i = 0; i < match.missingIngredients.size() && preview.length() < 20; i++) {
                    preview.append("、❌").append(match.missingIngredients.get(i));
                }
            }
            holder.tvIngredients.setText(preview.toString());
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onRecipeClick(item.recipeId);
        });
        holder.btnAddCart.setOnClickListener(v -> {
            if (listener != null) listener.onAddToCart(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvTitle;
        TextView tvMatch;
        TextView tvCalories;
        TextView tvIngredients;
        View btnAddCart;

        ViewHolder(View view) {
            super(view);
            ivImage = view.findViewById(R.id.iv_recommend_image);
            tvTitle = view.findViewById(R.id.tv_recommend_title);
            tvMatch = view.findViewById(R.id.tv_recommend_match);
            tvCalories = view.findViewById(R.id.tv_recommend_calories);
            tvIngredients = view.findViewById(R.id.tv_recommend_ingredients);
            btnAddCart = view.findViewById(R.id.btn_recommend_add_cart);
        }
    }
}
