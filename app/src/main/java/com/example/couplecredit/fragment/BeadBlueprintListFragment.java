package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BeadBlueprintAdapter;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BeadBlueprintListFragment extends Fragment {

    public static class BlueprintColorDraft {
        public final String colorCode;
        public final String quantityPerBuild;

        public BlueprintColorDraft(String colorCode, String quantityPerBuild) {
            this.colorCode = colorCode;
            this.quantityPerBuild = quantityPerBuild;
        }
    }

    private static class ParsedBlueprintColors {
        final List<BlueprintColorDraft> drafts = new ArrayList<>();
        int invalidLineNumber = 0;
    }

    private BeadInventoryViewModel viewModel;
    private BeadBlueprintAdapter adapter;
    private TextView tvSummary;
    private TextView tvEmpty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bead_blueprint_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(BeadInventoryViewModel.class);
        tvSummary = view.findViewById(R.id.tv_blueprint_list_summary);
        tvEmpty = view.findViewById(R.id.tv_blueprint_empty);

        RecyclerView recyclerView = view.findViewById(R.id.rv_bead_blueprints);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BeadBlueprintAdapter();
        adapter.setOnBlueprintClickListener(this::openDetail);
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_blueprint_list_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.fab_add_bead_blueprint).setOnClickListener(v -> showCreateDialog());

        viewModel.getDataVersion().observe(getViewLifecycleOwner(), version -> bindData());
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });
        if (!viewModel.hasBlueprintData()) {
            viewModel.loadBlueprints();
        } else {
            bindData();
        }
    }

    public static List<AuthApiModels.BeadBlueprintColorRequest> buildColorRequests(List<BlueprintColorDraft> drafts) {
        List<AuthApiModels.BeadBlueprintColorRequest> requests = new ArrayList<>();
        if (drafts == null) {
            return requests;
        }
        for (BlueprintColorDraft draft : drafts) {
            if (draft == null || draft.colorCode == null || draft.quantityPerBuild == null) {
                continue;
            }
            String code = draft.colorCode.trim().toUpperCase(Locale.ROOT);
            code = com.example.couplecredit.utils.BeadUtils.normalizeColorCode(code);
            if (code == null || code.isEmpty()) {
                continue;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(draft.quantityPerBuild.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (quantity <= 0) {
                continue;
            }
            requests.add(new AuthApiModels.BeadBlueprintColorRequest(code, quantity));
        }
        return requests;
    }

    private void bindData() {
        List<BeadInventoryViewModel.BeadBlueprintItem> items = viewModel.getBlueprintList();
        adapter.submitList(items);
        tvSummary.setText(String.format(Locale.getDefault(), "%d 张图纸 · 制作不会自动扣库存", items.size()));
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showCreateDialog() {
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bead_blueprint_editor, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(content).create();
        EditText etName = content.findViewById(R.id.et_blueprint_name);
        EditText etColors = content.findViewById(R.id.et_blueprint_colors);
        content.findViewById(R.id.btn_blueprint_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btn_blueprint_dialog_save).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(requireContext(), "请输入图纸名称", Toast.LENGTH_SHORT).show();
                return;
            }
            ParsedBlueprintColors parsedColors = parseColorDrafts(etColors.getText().toString());
            if (parsedColors.invalidLineNumber > 0) {
                Toast.makeText(requireContext(), "第 " + parsedColors.invalidLineNumber + " 行格式有误，请按 A01 12 填写", Toast.LENGTH_SHORT).show();
                return;
            }
            List<AuthApiModels.BeadBlueprintColorRequest> requests = buildColorRequests(parsedColors.drafts);
            if (requests.size() != parsedColors.drafts.size()) {
                Toast.makeText(requireContext(), "请检查色号和数量，数量必须大于 0", Toast.LENGTH_SHORT).show();
                return;
            }
            if (requests.isEmpty()) {
                Toast.makeText(requireContext(), "请至少填写一个有效颜色", Toast.LENGTH_SHORT).show();
                return;
            }
            BeadUtils.createBlueprint(requireContext(), name, requests, new BeadUtils.BeadBlueprintCreateCallback() {
                @Override public void onSuccess(AuthApiModels.BeadBlueprintCreateResponse response) {
                    dialog.dismiss();
                    viewModel.loadBlueprints();
                }
                @Override public void onError(String error) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private ParsedBlueprintColors parseColorDrafts(String raw) {
        ParsedBlueprintColors parsed = new ParsedBlueprintColors();
        if (raw == null || raw.trim().isEmpty()) {
            return parsed;
        }
        String[] lines = raw.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("[\\s,，:：]+", 2);
            if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                parsed.invalidLineNumber = i + 1;
                return parsed;
            }
            parsed.drafts.add(new BlueprintColorDraft(parts[0], parts[1]));
        }
        return parsed;
    }

    private void openDetail(BeadInventoryViewModel.BeadBlueprintItem item) {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, BeadBlueprintDetailFragment.newInstance(item.blueprintId))
                .addToBackStack("bead_blueprint_detail")
                .commit();
    }
}
