package com.example.couplecredit.adapter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
        resetCardPresentation(holder);

        String categoryText = buildCategoryText(item);
        holder.tvName.setText(item.name);
        holder.tvCategory.setText(categoryText);
        String quantityText = buildQuantityText(item);
        holder.tvQuantity.setText(quantityText);
        holder.tvCompactName.setText(item.name);
        holder.tvCompactQuantity.setText(quantityText);
        holder.tvLastConsumed.setText(buildStatusText(item));
        bindExpirationStatus(holder, item);

        if (item.isLowStock()) {
            holder.tvLowStockBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tvLowStockBadge.setVisibility(View.GONE);
        }

        if (item.note != null && !item.note.trim().isEmpty()) {
            holder.tvNote.setVisibility(View.VISIBLE);
            holder.tvNote.setText(item.note);
        } else {
            // The note shares a fixed-height metadata line; GONE lets the other
            // metadata use that line's width without changing card geometry.
            holder.tvNote.setText(null);
            holder.tvNote.setVisibility(View.GONE);
        }

        // A recycled holder may still be loading the previous item's image.
        Glide.with(holder.ivImage.getContext()).clear(holder.ivImage);
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

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.ivImage.getContext()).clear(holder.ivImage);
        holder.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
        resetCardPresentation(holder);
        super.onViewRecycled(holder);
    }

    private void resetCardPresentation(@NonNull ViewHolder holder) {
        // Layout managers may temporarily swap these layers while a row is in
        // the roller.  Rebinding/recycling must restore the ordinary list
        // presentation for LinearLayoutManager and TalkBack.
        holder.itemView.setClipBounds(null);
        holder.itemView.setTranslationX(0f);
        holder.itemView.setTranslationY(0f);
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.itemView.setAlpha(1f);
        holder.itemView.setRotationX(0f);
        holder.itemView.setTranslationZ(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.itemView.setRenderEffect(null);
        }
        holder.layoutInventoryCard.setAlpha(1f);
        holder.layoutInventoryCard.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        holder.layoutInventoryCompact.setAlpha(0f);
        holder.layoutInventoryCompact.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void bindExpirationStatus(ViewHolder holder, InventoryItem item) {
        // The status row itself has a fixed height; an absent expiry can yield
        // its horizontal space to the date without changing deck geometry.
        holder.tvExpirationStatus.setVisibility(View.GONE);
        holder.tvExpirationStatus.setText(null);
        holder.tvExpirationStatus.setTextColor(Color.parseColor("#F59E0B"));

        String status = buildExpirationStatus(item);
        if (status == null) {
            return;
        }
        holder.tvExpirationStatus.setVisibility(View.VISIBLE);
        holder.tvExpirationStatus.setText(status);
        if (item.isExpired) {
            holder.tvExpirationStatus.setTextColor(Color.parseColor("#D14343"));
        } else {
            holder.tvExpirationStatus.setTextColor(Color.parseColor("#F59E0B"));
        }
    }

    private String buildExpirationStatus(InventoryItem item) {
        Calendar expiration = parseDate(item.expirationDate);
        if (expiration == null) {
            return null;
        }
        Calendar today = Calendar.getInstance();
        zeroTime(today);
        zeroTime(expiration);

        long diffMillis = expiration.getTimeInMillis() - today.getTimeInMillis();
        int diffDays = (int) (diffMillis / (24L * 60L * 60L * 1000L));

        if (diffDays < 0 || item.isExpired) {
            return "已过期";
        }
        if (diffDays == 0) {
            return "今天到期";
        }
        if (diffDays <= 3 || item.isExpiring) {
            return diffDays + " 天后到期";
        }
        return null;
    }

    private Calendar parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            format.setLenient(false);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(format.parse(value.trim()));
            return calendar;
        } catch (ParseException | NullPointerException e) {
            return null;
        }
    }

    private void zeroTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private String buildStatusText(InventoryItem item) {
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) {
            return "消耗 " + formatDate(item.lastConsumedAt);
        }
        if (item.updatedAt != null && !item.updatedAt.isEmpty()) {
            return "更新 " + formatDate(item.updatedAt);
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
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return "";
        }
        String normalized = dateStr.trim().replace('T', ' ');
        if (normalized.contains(".")) {
            normalized = normalized.substring(0, normalized.indexOf('.'));
        }
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            parser.setLenient(false);
            Date parsed = parser.parse(normalized);
            if (parsed != null) {
                return new SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(parsed);
            }
        } catch (ParseException ignored) {
            // 兼容服务端返回的非标准日期文本，下面保留可读的短形式。
        }
        if (normalized.length() >= 16) {
            return normalized.substring(5, 16);
        }
        return normalized;
    }

    private String trimQuantity(double value) {
        if (value == (long) value) {
            return String.format(Locale.getDefault(), "%d", (long) value);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private String buildCategoryText(InventoryItem item) {
        if (item.category == null || item.category.trim().isEmpty()) {
            return "未分类";
        }
        return item.category;
    }

    private String buildQuantityText(InventoryItem item) {
        String quantity = trimQuantity(item.quantity);
        String unit = item.unit == null ? "" : item.unit.trim();
        return unit.isEmpty() ? quantity : quantity + " " + unit;
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View layoutInventoryCard;
        View layoutInventoryCompact;
        ImageView ivImage;
        TextView tvName;
        TextView tvCategory;
        TextView tvQuantity;
        TextView tvCompactName;
        TextView tvCompactQuantity;
        TextView tvLowStockBadge;
        TextView tvLastConsumed;
        TextView tvExpirationStatus;
        TextView tvNote;
        TextView btnConsume;
        TextView btnReplenish;
        ImageButton btnMore;

        ViewHolder(View itemView) {
            super(itemView);
            layoutInventoryCard = itemView.findViewById(R.id.layout_inventory_card);
            layoutInventoryCompact = itemView.findViewById(R.id.layout_inventory_compact);
            ivImage = itemView.findViewById(R.id.iv_inventory_image);
            tvName = itemView.findViewById(R.id.tv_inventory_name);
            tvCategory = itemView.findViewById(R.id.tv_inventory_category);
            tvQuantity = itemView.findViewById(R.id.tv_quantity);
            tvCompactName = itemView.findViewById(R.id.tv_inventory_compact_name);
            tvCompactQuantity = itemView.findViewById(R.id.tv_inventory_compact_quantity);
            tvLowStockBadge = itemView.findViewById(R.id.tv_low_stock_badge);
            tvLastConsumed = itemView.findViewById(R.id.tv_last_consumed);
            tvExpirationStatus = itemView.findViewById(R.id.tv_expiration_status);
            tvNote = itemView.findViewById(R.id.tv_note);
            btnConsume = itemView.findViewById(R.id.btn_consume);
            btnReplenish = itemView.findViewById(R.id.btn_replenish);
            btnMore = itemView.findViewById(R.id.btn_more);
        }
    }
}
