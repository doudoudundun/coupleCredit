package com.example.couplecredit.adapter;

import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TodoAdapter extends RecyclerView.Adapter<TodoAdapter.TodoHolder> {

    public interface TodoActionListener {
        void onToggleStatus(AuthApiModels.TodoItemData item);
        void onEdit(AuthApiModels.TodoItemData item);
        void onDelete(AuthApiModels.TodoItemData item);
    }

    private final List<AuthApiModels.TodoItemData> items = new ArrayList<>();
    private final Set<Integer> expandedTodoIds = new HashSet<>();
    private final TodoActionListener listener;

    public TodoAdapter(TodoActionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<AuthApiModels.TodoItemData> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TodoHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_todo, parent, false);
        return new TodoHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TodoHolder holder, int position) {
        AuthApiModels.TodoItemData item = items.get(position);
        boolean expanded = expandedTodoIds.contains(item.todoId);
        boolean done = "done".equals(item.status);
        boolean missed = "missed".equals(item.status);

        holder.tvTitle.setText(item.title);
        holder.tvPreview.setText(expanded ? "点击收起详情" : buildPreviewText(item));
        holder.tvContent.setText(item.content != null && !item.content.trim().isEmpty() ? item.content : "暂无详细内容");
        holder.tvFuzzyDate.setText(item.fuzzyDateText != null && !item.fuzzyDateText.trim().isEmpty() ? item.fuzzyDateText : "未设置时间");
        holder.layoutExpanded.setVisibility(expanded ? View.VISIBLE : View.GONE);

        bindPriority(holder.tvPriority, item.priority);
        bindStatus(holder, item.status, done, missed);
        bindImages(holder, item.imageUrl);

        holder.itemView.setOnClickListener(v -> {
            if (expanded) {
                expandedTodoIds.remove(item.todoId);
            } else {
                expandedTodoIds.add(item.todoId);
            }
            notifyItemChanged(holder.getAdapterPosition());
        });

        holder.btnToggleStatus.setOnClickListener(v -> {
            if (listener != null) listener.onToggleStatus(item);
        });
        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(item);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(item);
        });
    }

    private String buildPreviewText(AuthApiModels.TodoItemData item) {
        if (item.content != null && !item.content.trim().isEmpty()) {
            return item.content;
        }
        return "点击展开详情";
    }

    private void bindPriority(TextView view, String priority) {
        if ("high".equals(priority)) {
            view.setText("高优先级");
            view.setBackgroundResource(R.drawable.todo_priority_chip_high);
            view.setTextColor(Color.parseColor("#B91C1C"));
        } else if ("low".equals(priority)) {
            view.setText("低优先级");
            view.setBackgroundResource(R.drawable.todo_priority_chip_low);
            view.setTextColor(Color.parseColor("#1D4ED8"));
        } else {
            view.setText("中优先级");
            view.setBackgroundResource(R.drawable.todo_priority_chip_medium);
            view.setTextColor(Color.parseColor("#92400E"));
        }
    }

    private void bindStatus(TodoHolder holder, String status, boolean done, boolean missed) {
        if (missed) {
            holder.tvStatus.setText("已错过");
            holder.tvStatus.setBackgroundResource(R.drawable.todo_status_missed_background);
            holder.btnToggleStatus.setText("恢复未处理");
        } else if (done) {
            holder.tvStatus.setText("已处理");
            holder.tvStatus.setBackgroundResource(R.drawable.todo_status_done_background);
            holder.btnToggleStatus.setText("恢复未处理");
        } else {
            holder.tvStatus.setText("未处理");
            holder.tvStatus.setBackgroundResource(R.drawable.category_tag_background);
            holder.btnToggleStatus.setText("标记完成");
        }
        holder.tvTitle.setPaintFlags(done || missed
                ? holder.tvTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : holder.tvTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
        ((CardView) holder.itemView).setCardElevation(done || missed ? 1f : 2f);
        holder.layoutCardRoot.setAlpha(done || missed ? 0.78f : 1f);
    }

    private void bindImages(TodoHolder holder, String imageUrl) {
        if (imageUrl != null && !imageUrl.isEmpty()) {
            String resolvedUrl = ApiConfigManager.resolveResourceUrl(holder.ivImage.getContext(), imageUrl);
            holder.ivImage.setVisibility(View.VISIBLE);
            Glide.with(holder.ivImage.getContext())
                    .load(resolvedUrl)
                    .placeholder(R.drawable.ic_inventory_placeholder)
                    .error(R.drawable.ic_inventory_placeholder)
                    .centerCrop()
                    .into(holder.ivImage);
            holder.ivLargeImage.setVisibility(View.VISIBLE);
            Glide.with(holder.ivLargeImage.getContext())
                    .load(resolvedUrl)
                    .placeholder(R.drawable.ic_inventory_placeholder)
                    .error(R.drawable.ic_inventory_placeholder)
                    .centerCrop()
                    .into(holder.ivLargeImage);
        } else {
            holder.ivImage.setVisibility(View.GONE);
            holder.ivLargeImage.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class TodoHolder extends RecyclerView.ViewHolder {
        LinearLayout layoutCardRoot;
        LinearLayout layoutTextContent;
        TextView tvPriority;
        TextView tvStatus;
        TextView tvFuzzyDate;
        TextView tvTitle;
        TextView tvPreview;
        TextView tvContent;
        TextView btnToggleStatus;
        TextView btnEdit;
        TextView btnDelete;
        LinearLayout layoutExpanded;
        ImageView ivImage;
        ImageView ivLargeImage;

        TodoHolder(View itemView) {
            super(itemView);
            layoutCardRoot = itemView.findViewById(R.id.layout_card_root);
            layoutTextContent = itemView.findViewById(R.id.layout_text_content);
            tvPriority = itemView.findViewById(R.id.tv_priority);
            tvStatus = itemView.findViewById(R.id.tv_status);
            tvFuzzyDate = itemView.findViewById(R.id.tv_fuzzy_date);
            tvTitle = itemView.findViewById(R.id.tv_todo_title);
            tvPreview = itemView.findViewById(R.id.tv_todo_preview);
            tvContent = itemView.findViewById(R.id.tv_todo_content);
            btnToggleStatus = itemView.findViewById(R.id.btn_toggle_status);
            btnEdit = itemView.findViewById(R.id.btn_edit_todo);
            btnDelete = itemView.findViewById(R.id.btn_delete_todo);
            layoutExpanded = itemView.findViewById(R.id.layout_expanded);
            ivImage = itemView.findViewById(R.id.iv_todo_image);
            ivLargeImage = itemView.findViewById(R.id.iv_todo_large_image);
        }
    }
}
