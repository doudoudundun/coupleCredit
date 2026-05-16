package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.TodoAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.DataLocalCache;
import com.example.couplecredit.utils.ImageCompressor;
import com.example.couplecredit.utils.UserInfoManager;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TodoFragment extends Fragment implements TodoAdapter.TodoActionListener {

    private RecyclerView rvTodoList;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private View layoutContent;
    private TextView btnLoginPrompt;
    private TextView tvTodoCount;
    private TextView chipAll;
    private TextView chipOpen;
    private TextView chipDone;
    private TextView chipMissed;
    private TextView chipPriorityAll;
    private TextView chipPriorityHigh;
    private TextView chipPriorityMedium;
    private TextView chipPriorityLow;
    private View fabAddTodo;
    private View fabRefreshTodo;
    private TodoAdapter todoAdapter;
    private final List<AuthApiModels.TodoItemData> allTodos = new ArrayList<>();
    private final Gson gson = new Gson();
    private String activeFilter = "all";
    private String activePriorityFilter = "all";
    private ImageView pendingImageView;
    private String pendingImageUrl;
    private boolean imageChanged = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_todo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rvTodoList = view.findViewById(R.id.rv_todo_list);
        llEmptyState = view.findViewById(R.id.ll_empty_state);
        layoutLoginPrompt = view.findViewById(R.id.layout_login_prompt);
        layoutContent = view.findViewById(R.id.layout_content);
        btnLoginPrompt = view.findViewById(R.id.btn_login_prompt);
        tvTodoCount = view.findViewById(R.id.tv_todo_count);
        chipAll = view.findViewById(R.id.chip_filter_all);
        chipOpen = view.findViewById(R.id.chip_filter_open);
        chipDone = view.findViewById(R.id.chip_filter_done);
        chipMissed = view.findViewById(R.id.chip_filter_missed);
        chipPriorityAll = view.findViewById(R.id.chip_priority_all);
        chipPriorityHigh = view.findViewById(R.id.chip_priority_high);
        chipPriorityMedium = view.findViewById(R.id.chip_priority_medium);
        chipPriorityLow = view.findViewById(R.id.chip_priority_low);
        fabAddTodo = view.findViewById(R.id.fab_add_todo);
        fabRefreshTodo = view.findViewById(R.id.fab_refresh_todo);

        rvTodoList.setLayoutManager(new LinearLayoutManager(getContext()));
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(260L);
        animator.setAddDuration(220L);
        animator.setRemoveDuration(220L);
        animator.setChangeDuration(180L);
        rvTodoList.setItemAnimator(animator);
        todoAdapter = new TodoAdapter(this);
        rvTodoList.setAdapter(todoAdapter);

        chipAll.setOnClickListener(v -> setStatusFilter("all"));
        chipOpen.setOnClickListener(v -> setStatusFilter("open"));
        chipDone.setOnClickListener(v -> setStatusFilter("done"));
        chipMissed.setOnClickListener(v -> setStatusFilter("missed"));
        chipPriorityAll.setOnClickListener(v -> setPriorityFilter("all"));
        chipPriorityHigh.setOnClickListener(v -> setPriorityFilter("high"));
        chipPriorityMedium.setOnClickListener(v -> setPriorityFilter("medium"));
        chipPriorityLow.setOnClickListener(v -> setPriorityFilter("low"));
        btnLoginPrompt.setOnClickListener(v -> startActivity(new Intent(requireContext(), LoginActivity.class)));
        fabAddTodo.setOnClickListener(v -> {
            if (!UserInfoManager.isUserLoggedIn(requireContext())) {
                startActivity(new Intent(requireContext(), LoginActivity.class));
                return;
            }
            showTodoDialog(null);
        });
        fabRefreshTodo.setOnClickListener(v -> refreshData());

        setStatusFilter("all");
        setPriorityFilter("all");
        refreshData();
    }

    public void refreshData() {
        if (!isAdded()) return;
        boolean isLoggedIn = UserInfoManager.isUserLoggedIn(requireContext());
        layoutLoginPrompt.setVisibility(isLoggedIn ? View.GONE : View.VISIBLE);
        layoutContent.setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
        fabAddTodo.setEnabled(isLoggedIn);
        fabAddTodo.setAlpha(isLoggedIn ? 1f : 0.5f);
        if (!isLoggedIn) {
            allTodos.clear();
            todoAdapter.submitList(new ArrayList<>());
            updateEmptyState(0);
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(requireContext());

        if (allTodos.isEmpty()) {
            String cached = DataLocalCache.get(requireContext(), "todos_" + userId);
            if (cached != null) {
                try {
                    AuthApiModels.TodoListResponse r = gson.fromJson(cached, AuthApiModels.TodoListResponse.class);
                    if (r != null && r.data != null && r.data.items != null) {
                        allTodos.clear();
                        allTodos.addAll(r.data.items);
                        applyFilter();
                    }
                } catch (Exception ignored) {}
            }
        }

        AuthApiClient.queryTodos(requireContext(), userId, new AuthApiClient.TodoListCallback() {
            @Override
            public void onSuccess(AuthApiModels.TodoListResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    allTodos.clear();
                    if (response != null && response.data != null && response.data.items != null) {
                        allTodos.addAll(response.data.items);
                    }
                    applyFilter();
                    DataLocalCache.put(requireContext(), "todos_" + userId, gson.toJson(response));
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void setStatusFilter(String filter) {
        activeFilter = filter;
        updateFilterState();
        applyFilter();
    }

    private void setPriorityFilter(String filter) {
        activePriorityFilter = filter;
        updatePriorityFilterState();
        applyFilter();
    }

    private void updateFilterState() {
        bindChip(chipAll, "all".equals(activeFilter));
        bindChip(chipOpen, "open".equals(activeFilter));
        bindChip(chipDone, "done".equals(activeFilter));
        bindChip(chipMissed, "missed".equals(activeFilter));
    }

    private void updatePriorityFilterState() {
        bindChip(chipPriorityAll, "all".equals(activePriorityFilter));
        bindChip(chipPriorityHigh, "high".equals(activePriorityFilter));
        bindChip(chipPriorityMedium, "medium".equals(activePriorityFilter));
        bindChip(chipPriorityLow, "low".equals(activePriorityFilter));
    }

    private void bindChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.button_background : R.drawable.category_tag_background);
        chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF4B5563);
    }

    private void applyFilter() {
        List<AuthApiModels.TodoItemData> filtered = new ArrayList<>();
        for (AuthApiModels.TodoItemData item : allTodos) {
            if ("open".equals(activeFilter) && !"open".equals(item.status)) continue;
            if ("done".equals(activeFilter) && !"done".equals(item.status)) continue;
            if ("missed".equals(activeFilter) && !"missed".equals(item.status)) continue;
            if ("all".equals(activeFilter) && "done".equals(item.status)) continue;
            if (!"all".equals(activePriorityFilter) && !activePriorityFilter.equals(item.priority)) continue;
            filtered.add(item);
        }
        Collections.sort(filtered, new Comparator<AuthApiModels.TodoItemData>() {
            @Override
            public int compare(AuthApiModels.TodoItemData left, AuthApiModels.TodoItemData right) {
                int statusCompare = Integer.compare(statusRank(left.status), statusRank(right.status));
                if (statusCompare != 0) return statusCompare;
                int priorityCompare = Integer.compare(priorityRank(left.priority), priorityRank(right.priority));
                if (priorityCompare != 0) return priorityCompare;
                return Long.compare(right.todoId, left.todoId);
            }
        });
        todoAdapter.submitList(filtered);
        tvTodoCount.setText(filtered.size() + " 项");
        updateEmptyState(filtered.size());
    }

    private int statusRank(String status) {
        if ("open".equals(status)) return 0;
        if ("missed".equals(status)) return 1;
        return 2;
    }

    private int priorityRank(String priority) {
        if ("high".equals(priority)) return 0;
        if ("medium".equals(priority)) return 1;
        return 2;
    }

    private void updateEmptyState(int filteredCount) {
        llEmptyState.setVisibility(filteredCount == 0 ? View.VISIBLE : View.GONE);
    }

    static Long resolveSeriesIdForSave(int todoId, @Nullable Long existingSeriesId, boolean isRepeatable) {
        if (!isRepeatable) {
            return null;
        }
        if (existingSeriesId != null) {
            return existingSeriesId;
        }
        return todoId > 0 ? Long.valueOf(todoId) : null;
    }

    static int resolveCompletedCountForToggle(String currentStatus, boolean isRepeatable, int currentCompletedCount) {
        if ("open".equals(currentStatus) && isRepeatable) {
            return currentCompletedCount + 1;
        }
        return currentCompletedCount;
    }

    static boolean shouldAutoDuplicateAfterCompletion(String currentStatus, boolean isRepeatable) {
        return "open".equals(currentStatus) && isRepeatable;
    }

    @Override
    public void onToggleStatus(AuthApiModels.TodoItemData item) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        String nextStatus = "open";
        boolean isCompleting = "open".equals(item.status);
        if (isCompleting) {
            nextStatus = "done";
        }
        int nextCompletedCount = resolveCompletedCountForToggle(item.status, item.isRepeatable, item.completedCount);
        boolean shouldAutoDuplicate = shouldAutoDuplicateAfterCompletion(item.status, item.isRepeatable);

        final int removedPosition;
        if (isCompleting) {
            removedPosition = todoAdapter.removeItem(item.todoId);
        } else {
            removedPosition = -1;
            todoAdapter.animateReopenChange(item);
        }

        AuthApiClient.updateTodo(requireContext(), item.todoId,
                new AuthApiModels.UpdateTodoRequest(userId, item.title, item.content, item.priority, item.fuzzyDateText, item.imageUrl, nextStatus,
                        item.isRepeatable, item.seriesId, nextCompletedCount),
                new AuthApiClient.SimpleCallback() {
                    @Override
                    public void onSuccess() {
                        if (!isAdded()) return;
                        if (shouldAutoDuplicate) {
                            AuthApiClient.duplicateTodo(requireContext(), item.todoId, userId, new AuthApiClient.SimpleCallback() {
                                @Override
                                public void onSuccess() {
                                    if (!isAdded()) return;
                                    requireActivity().runOnUiThread(TodoFragment.this::refreshData);
                                }

                                @Override
                                public void onError(String message) {
                                    if (!isAdded()) return;
                                    requireActivity().runOnUiThread(() -> {
                                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                                        refreshData();
                                    });
                                }
                            });
                            return;
                        }
                        requireActivity().runOnUiThread(TodoFragment.this::refreshData);
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (removedPosition >= 0) {
                                todoAdapter.addItemAt(removedPosition, item);
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                            refreshData();
                        });
                    }
                });
    }

    @Override
    public void onEdit(AuthApiModels.TodoItemData item) {
        showTodoDialog(item);
    }

    @Override
    public void onDelete(AuthApiModels.TodoItemData item) {
        new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setTitle("删除代办")
                .setMessage("确定删除\"" + item.title + "\"吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    int userId = UserInfoManager.getCurrentUserId(requireContext());
                    AuthApiClient.deleteTodo(requireContext(), item.todoId, userId, new AuthApiClient.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(TodoFragment.this::refreshData);
                        }

                        @Override
                        public void onError(String message) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onDuplicate(AuthApiModels.TodoItemData item) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        todoAdapter.setDuplicateInFlight(item.todoId, true);
        AuthApiClient.duplicateTodo(requireContext(), item.todoId, userId, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    todoAdapter.setDuplicateInFlight(item.todoId, false);
                    refreshData();
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    todoAdapter.setDuplicateInFlight(item.todoId, false);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onRemindPartner(AuthApiModels.TodoItemData item) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiClient.remindPartner(requireContext(), item.todoId, userId, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "已提醒伴侣完成「" + item.title + "」", Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void showTodoDialog(@Nullable AuthApiModels.TodoItemData existing) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_todo, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        EditText etTitle = dialogView.findViewById(R.id.et_todo_title);
        EditText etFuzzyDate = dialogView.findViewById(R.id.et_todo_fuzzy_date);
        Spinner spinnerPriority = dialogView.findViewById(R.id.spinner_priority);
        Spinner spinnerStatus = dialogView.findViewById(R.id.spinner_status);
        EditText etContent = dialogView.findViewById(R.id.et_todo_content);
        ImageView ivAddImage = dialogView.findViewById(R.id.iv_add_image);
        View flAddImage = dialogView.findViewById(R.id.fl_add_image);
        TextView btnSave = dialogView.findViewById(R.id.btn_save_todo);
        android.widget.Switch switchRepeatable = dialogView.findViewById(R.id.switch_repeatable);

        List<String> priorityOptions = Arrays.asList("高优先级", "中优先级", "低优先级");
        ArrayAdapter<String> priorityAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, priorityOptions);
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPriority.setAdapter(priorityAdapter);

        List<String> statusOptions = Arrays.asList("未处理", "已处理", "已错过");
        ArrayAdapter<String> statusAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, statusOptions);
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStatus.setAdapter(statusAdapter);

        pendingImageUrl = null;
        imageChanged = false;

        if (existing != null) {
            tvDialogTitle.setText("编辑代办");
            btnSave.setText("保存修改");
            etTitle.setText(existing.title);
            etFuzzyDate.setText(existing.fuzzyDateText != null ? existing.fuzzyDateText : "");
            etContent.setText(existing.content != null ? existing.content : "");
            switchRepeatable.setChecked(existing.isRepeatable);
            pendingImageUrl = existing.imageUrl;
            if (existing.imageUrl != null && !existing.imageUrl.isEmpty()) {
                String resolvedUrl = ApiConfigManager.resolveResourceUrl(requireContext(), existing.imageUrl);
                Glide.with(this).load(resolvedUrl).placeholder(R.drawable.ic_inventory_placeholder).into(ivAddImage);
            }
            spinnerPriority.setSelection(priorityToPosition(existing.priority));
            spinnerStatus.setSelection(statusToPosition(existing.status));
        }

        flAddImage.setOnClickListener(v -> {
            pendingImageView = ivAddImage;
            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivityForResult(intent, 3001);
            }
        });

        btnSave.setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            if (TextUtils.isEmpty(title)) {
                Toast.makeText(requireContext(), "请输入标题", Toast.LENGTH_SHORT).show();
                return;
            }
            String fuzzyDate = etFuzzyDate.getText().toString().trim();
            String content = etContent.getText().toString().trim();
            String priority = positionToPriority(spinnerPriority.getSelectedItemPosition());
            String status = positionToStatus(spinnerStatus.getSelectedItemPosition());
            boolean isRepeatable = switchRepeatable.isChecked();
            int userId = UserInfoManager.getCurrentUserId(requireContext());

            Runnable saveAction = () -> saveTodo(dialog, existing, userId, title, content, priority, fuzzyDate, pendingImageUrl, status,
                    isRepeatable, resolveSeriesIdForSave(existing != null ? existing.todoId : 0, existing != null ? existing.seriesId : null, isRepeatable),
                    existing != null ? existing.completedCount : 0);
            if (imageChanged && pendingImageUrl != null) {
                try {
                    Uri imageUri = Uri.parse(pendingImageUrl);
                    byte[] compressedBytes = ImageCompressor.compress(requireContext(), imageUri, 1024, 80);
                    InputStream compressed = compressedBytes != null ? new ByteArrayInputStream(compressedBytes) : null;
                    if (compressed != null) {
                        String fileName = "todo_" + System.currentTimeMillis() + ".jpg";
                        AuthApiClient.uploadImage(requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                            @Override
                            public void onSuccess(String imageUrl) {
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() -> saveTodo(dialog, existing, userId, title, content, priority, fuzzyDate, imageUrl, status,
                                        isRepeatable, resolveSeriesIdForSave(existing != null ? existing.todoId : 0, existing != null ? existing.seriesId : null, isRepeatable),
                                        existing != null ? existing.completedCount : 0));
                            }

                            @Override
                            public void onError(String message) {
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
                            }
                        });
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            saveAction.run();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void saveTodo(AlertDialog dialog, @Nullable AuthApiModels.TodoItemData existing, int userId, String title, String content, String priority,
                          String fuzzyDate, String imageUrl, String status, boolean isRepeatable, Long seriesId, int completedCount) {
        AuthApiClient.SimpleCallback callback = new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    dialog.dismiss();
                    refreshData();
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
            }
        };

        if (existing == null) {
            AuthApiClient.createTodo(requireContext(), new AuthApiModels.CreateTodoRequest(userId, title, emptyToNull(content), priority, emptyToNull(fuzzyDate), emptyToNull(imageUrl), status,
                    isRepeatable, seriesId, completedCount), callback);
        } else {
            AuthApiClient.updateTodo(requireContext(), existing.todoId, new AuthApiModels.UpdateTodoRequest(userId, title, emptyToNull(content), priority, emptyToNull(fuzzyDate), emptyToNull(imageUrl), status,
                    isRepeatable, seriesId, completedCount), callback);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private int priorityToPosition(String priority) {
        if ("high".equals(priority)) return 0;
        if ("low".equals(priority)) return 2;
        return 1;
    }

    private String positionToPriority(int position) {
        if (position == 0) return "high";
        if (position == 2) return "low";
        return "medium";
    }

    private int statusToPosition(String status) {
        if ("done".equals(status)) return 1;
        if ("missed".equals(status)) return 2;
        return 0;
    }

    private String positionToStatus(int position) {
        if (position == 1) return "done";
        if (position == 2) return "missed";
        return "open";
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @NonNull Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 3001 && resultCode == android.app.Activity.RESULT_OK && data.getData() != null) {
            Uri uri = data.getData();
            if (pendingImageView != null) {
                Glide.with(this).load(uri).into(pendingImageView);
            }
            pendingImageUrl = uri.toString();
            imageChanged = true;
        }
    }
}
