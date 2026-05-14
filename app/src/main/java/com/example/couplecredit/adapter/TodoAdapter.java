package com.example.couplecredit.adapter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
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

    private static final String STATUS_OPEN = "open";
    private static final String STATUS_DONE = "done";
    private static final String STATUS_MISSED = "missed";
    private static final String PRIORITY_HIGH = "high";
    private static final String PRIORITY_LOW = "low";
    private static final String PAYLOAD_STATUS_TRANSITION = "status-transition";

    private static final long EXPAND_ANIMATION_DURATION = 220L;
    private static final long STATUS_ANIMATION_DURATION = 360L;

    public interface TodoActionListener {
        void onToggleStatus(AuthApiModels.TodoItemData item);
        void onEdit(AuthApiModels.TodoItemData item);
        void onDelete(AuthApiModels.TodoItemData item);
        void onDuplicate(AuthApiModels.TodoItemData item);
        void onRemindPartner(AuthApiModels.TodoItemData item);
    }

    private final List<AuthApiModels.TodoItemData> items = new ArrayList<>();
    private final Set<Integer> expandedTodoIds = new HashSet<>();
    private final Set<Integer> animatingDoneIds = new HashSet<>();
    private final Set<Integer> animatingReopenIds = new HashSet<>();
    private final Set<Integer> completedTodoIds = new HashSet<>();
    private final Set<Integer> duplicatingTodoIds = new HashSet<>();
    private final TodoActionListener listener;

    public TodoAdapter(TodoActionListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitList(List<AuthApiModels.TodoItemData> newItems) {
        List<AuthApiModels.TodoItemData> oldItems = new ArrayList<>(items);
        Set<Integer> newIds = new HashSet<>();
        for (AuthApiModels.TodoItemData item : newItems) {
            newIds.add(item.todoId);
        }
        expandedTodoIds.retainAll(newIds);
        completedTodoIds.retainAll(newIds);
        animatingReopenIds.retainAll(newIds);

        items.clear();
        items.addAll(newItems);

        if (oldItems.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        int commonCount = Math.min(oldItems.size(), newItems.size());
        for (int i = 0; i < commonCount; i++) {
            if (oldItems.get(i).todoId != newItems.get(i).todoId) {
                notifyDataSetChanged();
                return;
            }
        }

        if (oldItems.size() > newItems.size()) {
            for (int i = oldItems.size() - 1; i >= newItems.size(); i--) {
                notifyItemRemoved(i);
            }
        } else if (newItems.size() > oldItems.size()) {
            for (int i = oldItems.size(); i < newItems.size(); i++) {
                notifyItemInserted(i);
            }
        }

        for (int i = 0; i < commonCount; i++) {
            AuthApiModels.TodoItemData oldItem = oldItems.get(i);
            AuthApiModels.TodoItemData newItem = newItems.get(i);
            if (!sameContent(oldItem, newItem)) {
                notifyItemChanged(i);
            }
        }
    }

    public void animateStatusChange(AuthApiModels.TodoItemData item) {
        if (item == null) {
            return;
        }
        int position = findPositionById(item.todoId);
        if (position < 0) {
            return;
        }
        expandedTodoIds.add(item.todoId);
        animatingDoneIds.add(item.todoId);
        notifyItemChanged(position, PAYLOAD_STATUS_TRANSITION);
    }

    public void markCompletedForRefresh(int todoId) {
        expandedTodoIds.remove(todoId);
        completedTodoIds.add(todoId);
    }

    public void animateReopenChange(AuthApiModels.TodoItemData item) {
        if (item == null) return;
        int position = findPositionById(item.todoId);
        if (position < 0) return;
        animatingReopenIds.add(item.todoId);
        notifyItemChanged(position, PAYLOAD_STATUS_TRANSITION);
    }

    public void setDuplicateInFlight(int todoId, boolean inFlight) {
        if (inFlight) {
            duplicatingTodoIds.add(todoId);
        } else {
            duplicatingTodoIds.remove(todoId);
        }
        int position = findPositionById(todoId);
        if (position >= 0) {
            notifyItemChanged(position);
        }
    }

    boolean isDuplicateInFlight(int todoId) {
        return duplicatingTodoIds.contains(todoId);
    }

    public int removeItem(int todoId) {
        int position = findPositionById(todoId);
        if (position < 0) return -1;
        items.remove(position);
        notifyItemRemoved(position);
        return position;
    }

    public void addItemAt(int position, AuthApiModels.TodoItemData item) {
        if (position < 0 || position > items.size()) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        } else {
            items.add(position, item);
            notifyItemInserted(position);
        }
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).todoId;
    }

    @NonNull
    @Override
    public TodoHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_todo, parent, false);
        return new TodoHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TodoHolder holder, int position) {
        bind(holder, position, false);
    }

    @Override
    public void onBindViewHolder(@NonNull TodoHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_STATUS_TRANSITION)) {
            bind(holder, position, true);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    static boolean shouldShowExpandedContentDuringStatusTransition(boolean isAnimatingStatusTransition) {
        return !isAnimatingStatusTransition;
    }

    static boolean shouldRestoreExpandedStateAfterCompletion(boolean wasExpanded, boolean statusChangedToCompleted) {
        return wasExpanded && !statusChangedToCompleted;
    }

    static boolean shouldShowDuplicateAction(boolean isRepeatable, String status) {
        return isRepeatable && STATUS_DONE.equals(status);
    }

    static boolean shouldKeepCompletedItemVisible(int position, String status, boolean waitingForRefresh) {
        return !waitingForRefresh;
    }

    private void bind(@NonNull TodoHolder holder, int position, boolean animateStatus) {
        AuthApiModels.TodoItemData item = items.get(position);
        boolean expanded = expandedTodoIds.contains(item.todoId);
        boolean done = STATUS_DONE.equals(item.status);
        boolean missed = STATUS_MISSED.equals(item.status);
        boolean wasJustCompleted = completedTodoIds.remove(item.todoId);
        boolean restoreExpanded = shouldRestoreExpandedStateAfterCompletion(expanded, wasJustCompleted && done);
        if (expanded && !restoreExpanded) {
            expandedTodoIds.remove(item.todoId);
        }
        boolean animatingStatusTransition = animateStatus && animatingDoneIds.contains(item.todoId);
        boolean animatingReopenTransition = animateStatus && animatingReopenIds.contains(item.todoId);
        boolean showExpandedContent = restoreExpanded && shouldShowExpandedContentDuringStatusTransition(animatingStatusTransition);

        holder.itemView.animate().cancel();
        holder.layoutExpanded.animate().cancel();
        holder.viewTitleStrike.animate().cancel();
        holder.tvTitle.animate().cancel();
        holder.layoutCardRoot.animate().cancel();
        resetAnimatedState(holder, done || missed, showExpandedContent);

        boolean shouldKeepVisible = shouldKeepCompletedItemVisible(position, item.status, wasJustCompleted && done);
        holder.itemView.setVisibility(shouldKeepVisible ? View.VISIBLE : View.INVISIBLE);
        holder.itemView.setAlpha(shouldKeepVisible ? holder.layoutCardRoot.getAlpha() : 0f);

        holder.tvTitle.setText(item.title);
        holder.tvPreview.setText(showExpandedContent ? "点击收起详情" : buildPreviewText(item));
        holder.tvContent.setText(item.content != null && !item.content.trim().isEmpty() ? item.content : "暂无详细内容");
        holder.tvFuzzyDate.setText(item.fuzzyDateText != null && !item.fuzzyDateText.trim().isEmpty() ? item.fuzzyDateText : "未设置时间");
        holder.layoutExpanded.setVisibility(showExpandedContent ? View.VISIBLE : View.GONE);
        holder.tvRepeatBadge.setVisibility(item.isRepeatable ? View.VISIBLE : View.GONE);
        holder.tvCompletedCount.setVisibility(item.isRepeatable && done ? View.VISIBLE : View.GONE);
        holder.tvCompletedCount.setText("已完成 " + item.completedCount + " 次");
        boolean showDuplicateAction = shouldShowDuplicateAction(item.isRepeatable, item.status);
        boolean duplicateInFlight = duplicatingTodoIds.contains(item.todoId);
        holder.btnDuplicate.setVisibility(showDuplicateAction ? View.VISIBLE : View.GONE);
        holder.btnDuplicate.setEnabled(showDuplicateAction && !duplicateInFlight);
        holder.btnDuplicate.setAlpha(duplicateInFlight ? 0.45f : 1f);

        bindPriority(holder.tvPriority, item.priority);
        bindStatus(holder, item.status, done, missed);
        bindImages(holder, item.imageUrl);

        holder.itemView.setOnClickListener(v -> toggleExpanded(holder));
        holder.btnToggleStatus.setOnClickListener(v -> {
            if (listener != null) listener.onToggleStatus(item);
        });
        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(item);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(item);
        });
        holder.btnDuplicate.setOnClickListener(v -> {
            if (duplicatingTodoIds.contains(item.todoId)) return;
            if (listener != null) listener.onDuplicate(item);
        });

        boolean isShared = item.relationshipId != null && item.relationshipId > 0;
        holder.btnRemindPartner.setVisibility(isShared && !done ? View.VISIBLE : View.GONE);
        holder.btnRemindPartner.setOnClickListener(v -> {
            if (listener != null) listener.onRemindPartner(item);
        });

        if (animatingStatusTransition) {
            runStatusTransition(holder, item, done || missed, restoreExpanded);
        } else if (animatingReopenTransition) {
            runReopenTransition(holder, item);
        }
    }

    private void toggleExpanded(TodoHolder holder) {
        int position = holder.getAdapterPosition();
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        AuthApiModels.TodoItemData item = items.get(position);
        boolean expanded = expandedTodoIds.contains(item.todoId);
        if (expanded) {
            expandedTodoIds.remove(item.todoId);
            animateCollapse(holder, item);
        } else {
            expandedTodoIds.add(item.todoId);
            animateExpand(holder, item);
        }
    }

    private void animateExpand(TodoHolder holder, AuthApiModels.TodoItemData item) {
        holder.tvPreview.setText("点击收起详情");
        holder.layoutExpanded.setVisibility(View.VISIBLE);
        holder.layoutExpanded.setAlpha(0f);
        holder.layoutExpanded.setTranslationY(-12f);
        holder.layoutExpanded.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(EXPAND_ANIMATION_DURATION)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    private void animateCollapse(TodoHolder holder, AuthApiModels.TodoItemData item) {
        holder.layoutExpanded.animate()
                .alpha(0f)
                .translationY(-12f)
                .setDuration(EXPAND_ANIMATION_DURATION)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        holder.layoutExpanded.setVisibility(View.GONE);
                        holder.layoutExpanded.setAlpha(1f);
                        holder.layoutExpanded.setTranslationY(0f);
                        holder.tvPreview.setText(buildPreviewText(item));
                        holder.layoutExpanded.animate().setListener(null);
                    }
                })
                .start();
    }

    private void runStatusTransition(TodoHolder holder, AuthApiModels.TodoItemData item, boolean completed, boolean expanded) {
        holder.layoutExpanded.setVisibility(View.GONE);
        holder.layoutExpanded.setAlpha(1f);
        holder.layoutExpanded.setTranslationY(0f);
        holder.tvPreview.setText(expanded ? "点击收起详情" : buildPreviewText(item));
        holder.viewTitleStrike.setScaleX(0f);
        holder.viewTitleStrike.setAlpha(0f);
        holder.layoutCardRoot.setAlpha(1f);
        holder.layoutCardRoot.setTranslationX(0f);

        holder.viewTitleStrike.animate()
                .alpha(1f)
                .scaleX(1f)
                .setDuration(140L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> holder.layoutCardRoot.animate()
                        .translationX(completed ? holder.itemView.getWidth() * 0.22f : 0f)
                        .alpha(completed ? 0.52f : 1f)
                        .setDuration(STATUS_ANIMATION_DURATION)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            animatingDoneIds.remove(item.todoId);
                            int currentPosition = holder.getAdapterPosition();
                            if (currentPosition != RecyclerView.NO_POSITION) {
                                notifyItemChanged(currentPosition);
                            }
                        })
                        .start())
                .start();
    }

    private void runReopenTransition(TodoHolder holder, AuthApiModels.TodoItemData item) {
        holder.layoutExpanded.setVisibility(View.GONE);
        holder.viewTitleStrike.setPivotX(0f);
        holder.viewTitleStrike.setScaleX(1f);
        holder.viewTitleStrike.setAlpha(1f);
        holder.layoutCardRoot.setAlpha(0.52f);
        holder.layoutCardRoot.setTranslationX(holder.itemView.getWidth() * 0.22f);

        holder.layoutCardRoot.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(STATUS_ANIMATION_DURATION)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> holder.viewTitleStrike.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .setDuration(140L)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            animatingReopenIds.remove(item.todoId);
                            int currentPosition = holder.getAdapterPosition();
                            if (currentPosition != RecyclerView.NO_POSITION) {
                                notifyItemChanged(currentPosition);
                            }
                        })
                        .start())
                .start();
    }

    private void resetAnimatedState(TodoHolder holder, boolean completed, boolean expanded) {
        holder.layoutCardRoot.setTranslationX(0f);
        holder.layoutCardRoot.setAlpha(completed ? 0.78f : 1f);
        holder.viewTitleStrike.setPivotX(0f);
        holder.viewTitleStrike.setScaleX(completed ? 1f : 0f);
        holder.viewTitleStrike.setAlpha(completed ? 1f : 0f);
        holder.layoutExpanded.setAlpha(1f);
        holder.layoutExpanded.setTranslationY(0f);
        holder.layoutExpanded.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private int findPositionById(int todoId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).todoId == todoId) {
                return i;
            }
        }
        return -1;
    }

    private boolean sameContent(AuthApiModels.TodoItemData oldItem, AuthApiModels.TodoItemData newItem) {
        return equals(oldItem.title, newItem.title)
                && equals(oldItem.content, newItem.content)
                && equals(oldItem.priority, newItem.priority)
                && equals(oldItem.fuzzyDateText, newItem.fuzzyDateText)
                && equals(oldItem.imageUrl, newItem.imageUrl)
                && equals(oldItem.status, newItem.status)
                && oldItem.isRepeatable == newItem.isRepeatable
                && equals(oldItem.seriesId == null ? null : String.valueOf(oldItem.seriesId), newItem.seriesId == null ? null : String.valueOf(newItem.seriesId))
                && oldItem.completedCount == newItem.completedCount;
    }

    private boolean equals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String buildPreviewText(AuthApiModels.TodoItemData item) {
        if (item.content != null && !item.content.trim().isEmpty()) {
            return item.content;
        }
        return "点击展开详情";
    }

    private void bindPriority(TextView view, String priority) {
        if (PRIORITY_HIGH.equals(priority)) {
            view.setText("高优先级");
            view.setBackgroundResource(R.drawable.todo_priority_chip_high);
            view.setTextColor(Color.parseColor("#B91C1C"));
        } else if (PRIORITY_LOW.equals(priority)) {
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
        holder.tvTitle.setPaintFlags(holder.tvTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
        ((CardView) holder.itemView).setCardElevation(done || missed ? 1f : 2f);
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
        TextView tvRepeatBadge;
        TextView tvCompletedCount;
        TextView btnToggleStatus;
        TextView btnDuplicate;
        TextView btnEdit;
        TextView btnDelete;
        TextView btnRemindPartner;
        LinearLayout layoutExpanded;
        ImageView ivImage;
        ImageView ivLargeImage;
        View viewTitleStrike;

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
            tvRepeatBadge = itemView.findViewById(R.id.tv_repeat_badge);
            tvCompletedCount = itemView.findViewById(R.id.tv_completed_count);
            btnToggleStatus = itemView.findViewById(R.id.btn_toggle_status);
            btnDuplicate = itemView.findViewById(R.id.btn_duplicate_todo);
            btnEdit = itemView.findViewById(R.id.btn_edit_todo);
            btnDelete = itemView.findViewById(R.id.btn_delete_todo);
            btnRemindPartner = itemView.findViewById(R.id.btn_remind_partner);
            layoutExpanded = itemView.findViewById(R.id.layout_expanded);
            ivImage = itemView.findViewById(R.id.iv_todo_image);
            ivLargeImage = itemView.findViewById(R.id.iv_todo_large_image);
            viewTitleStrike = itemView.findViewById(R.id.view_title_strike);
        }
    }
}
