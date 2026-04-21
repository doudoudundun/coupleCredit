package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.TodoAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.UserInfoManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TodoFragment extends Fragment implements TodoAdapter.TodoActionListener {

    private static final int IMAGE_MAX_DIMENSION = 1024;
    private static final int IMAGE_JPEG_QUALITY = 80;

    private RecyclerView rvTodoList;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private View layoutContent;
    private TextView btnLoginPrompt;
    private TextView tvTodoCount;
    private TextView chipAll;
    private TextView chipOpen;
    private TextView chipDone;
    private View fabAddTodo;
    private TodoAdapter todoAdapter;
    private final List<AuthApiModels.TodoItemData> allTodos = new ArrayList<>();
    private String activeFilter = "all";
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
        fabAddTodo = view.findViewById(R.id.fab_add_todo);

        rvTodoList.setLayoutManager(new LinearLayoutManager(getContext()));
        todoAdapter = new TodoAdapter(this);
        rvTodoList.setAdapter(todoAdapter);

        chipAll.setOnClickListener(v -> setFilter("all"));
        chipOpen.setOnClickListener(v -> setFilter("open"));
        chipDone.setOnClickListener(v -> setFilter("done"));
        btnLoginPrompt.setOnClickListener(v -> startActivity(new Intent(requireContext(), LoginActivity.class)));
        fabAddTodo.setOnClickListener(v -> {
            if (!UserInfoManager.isUserLoggedIn(requireContext())) {
                startActivity(new Intent(requireContext(), LoginActivity.class));
                return;
            }
            showTodoDialog(null);
        });

        setFilter("all");
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
            updateEmptyState();
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(requireContext());
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
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void setFilter(String filter) {
        activeFilter = filter;
        updateFilterState();
        applyFilter();
    }

    private void updateFilterState() {
        bindChip(chipAll, "all".equals(activeFilter));
        bindChip(chipOpen, "open".equals(activeFilter));
        bindChip(chipDone, "done".equals(activeFilter));
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
            filtered.add(item);
        }
        todoAdapter.submitList(filtered);
        tvTodoCount.setText(allTodos.size() + " 项");
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = allTodos.isEmpty() || ("open".equals(activeFilter) && countByStatus("open") == 0) || ("done".equals(activeFilter) && countByStatus("done") == 0);
        llEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private int countByStatus(String status) {
        int count = 0;
        for (AuthApiModels.TodoItemData item : allTodos) {
            if (status.equals(item.status)) count++;
        }
        return count;
    }

    @Override
    public void onToggleStatus(AuthApiModels.TodoItemData item) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        String nextStatus = "done".equals(item.status) ? "open" : "done";
        AuthApiClient.updateTodo(requireContext(), item.todoId,
                new AuthApiModels.UpdateTodoRequest(userId, item.title, item.content, item.priority, item.fuzzyDateText, item.imageUrl, nextStatus),
                new AuthApiClient.SimpleCallback() {
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

        List<String> priorityOptions = Arrays.asList("高优先级", "中优先级", "低优先级");
        ArrayAdapter<String> priorityAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, priorityOptions);
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPriority.setAdapter(priorityAdapter);

        List<String> statusOptions = Arrays.asList("未处理", "已处理");
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
            pendingImageUrl = existing.imageUrl;
            if (existing.imageUrl != null && !existing.imageUrl.isEmpty()) {
                String resolvedUrl = ApiConfigManager.resolveResourceUrl(requireContext(), existing.imageUrl);
                Glide.with(this).load(resolvedUrl).placeholder(R.drawable.ic_inventory_placeholder).into(ivAddImage);
            }
            spinnerPriority.setSelection(priorityToPosition(existing.priority));
            spinnerStatus.setSelection("done".equals(existing.status) ? 1 : 0);
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
            String status = spinnerStatus.getSelectedItemPosition() == 1 ? "done" : "open";
            int userId = UserInfoManager.getCurrentUserId(requireContext());

            Runnable saveAction = () -> saveTodo(dialog, existing, userId, title, content, priority, fuzzyDate, pendingImageUrl, status);
            if (imageChanged && pendingImageUrl != null) {
                try {
                    Uri imageUri = Uri.parse(pendingImageUrl);
                    InputStream compressed = compressImage(imageUri);
                    if (compressed != null) {
                        String fileName = "todo_" + System.currentTimeMillis() + ".jpg";
                        AuthApiClient.uploadImage(requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                            @Override
                            public void onSuccess(String imageUrl) {
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() -> saveTodo(dialog, existing, userId, title, content, priority, fuzzyDate, imageUrl, status));
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

    private void saveTodo(AlertDialog dialog, @Nullable AuthApiModels.TodoItemData existing, int userId, String title, String content, String priority, String fuzzyDate, String imageUrl, String status) {
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
            AuthApiClient.createTodo(requireContext(), new AuthApiModels.CreateTodoRequest(userId, title, emptyToNull(content), priority, emptyToNull(fuzzyDate), emptyToNull(imageUrl), status), callback);
        } else {
            AuthApiClient.updateTodo(requireContext(), existing.todoId, new AuthApiModels.UpdateTodoRequest(userId, title, emptyToNull(content), priority, emptyToNull(fuzzyDate), emptyToNull(imageUrl), status), callback);
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

    private InputStream compressImage(Uri imageUri) {
        try {
            InputStream is = requireContext().getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            if (is != null) is.close();

            int sampleSize = 1;
            int halfW = bounds.outWidth / 2;
            int halfH = bounds.outHeight / 2;
            while ((halfW / sampleSize) >= IMAGE_MAX_DIMENSION && (halfH / sampleSize) >= IMAGE_MAX_DIMENSION) {
                sampleSize *= 2;
            }

            is = requireContext().getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            if (bitmap == null) return null;

            if (bitmap.getWidth() > IMAGE_MAX_DIMENSION || bitmap.getHeight() > IMAGE_MAX_DIMENSION) {
                float scale = Math.min((float) IMAGE_MAX_DIMENSION / bitmap.getWidth(), (float) IMAGE_MAX_DIMENSION / bitmap.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
                bitmap.recycle();
                bitmap = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos);
            bitmap.recycle();
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
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
