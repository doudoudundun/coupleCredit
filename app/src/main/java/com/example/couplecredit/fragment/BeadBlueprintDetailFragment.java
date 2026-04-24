package com.example.couplecredit.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BeadBlueprintColorAdapter;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BeadBlueprintDetailFragment extends Fragment {

    private static final String ARG_BLUEPRINT_ID = "blueprint_id";

    public static class BlueprintColorDisplayItem {
        public final String colorCode;
        public final String hexColor;
        public final int quantityPerBuild;
        public final int totalConsumed;
        public final boolean isTransparent;

        public BlueprintColorDisplayItem(String colorCode, String hexColor, int quantityPerBuild, int totalConsumed, boolean isTransparent) {
            this.colorCode = colorCode;
            this.hexColor = hexColor;
            this.quantityPerBuild = quantityPerBuild;
            this.totalConsumed = totalConsumed;
            this.isTransparent = isTransparent;
        }
    }

    private int blueprintId;
    private BeadInventoryViewModel viewModel;
    private BeadBlueprintColorAdapter adapter;
    private TextView tvTitle;
    private TextView tvTotal;
    private TextView tvBuilds;
    private BeadInventoryViewModel.BeadBlueprintItem currentItem;

    public static BeadBlueprintDetailFragment newInstance(int blueprintId) {
        BeadBlueprintDetailFragment fragment = new BeadBlueprintDetailFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_BLUEPRINT_ID, blueprintId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bead_blueprint_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        blueprintId = getArguments() == null ? 0 : getArguments().getInt(ARG_BLUEPRINT_ID, 0);
        viewModel = new ViewModelProvider(requireActivity()).get(BeadInventoryViewModel.class);
        tvTitle = view.findViewById(R.id.tv_blueprint_detail_title);
        tvTotal = view.findViewById(R.id.tv_blueprint_detail_total);
        tvBuilds = view.findViewById(R.id.tv_blueprint_detail_builds);

        RecyclerView recyclerView = view.findViewById(R.id.rv_blueprint_colors);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BeadBlueprintColorAdapter();
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_blueprint_detail_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.btn_build_blueprint).setOnClickListener(v -> buildOnce());

        if (!bindFromViewModel()) {
            loadDetail();
        }
    }

    public static int calculateTotalBeadsPerBuild(List<BlueprintColorDisplayItem> colors) {
        int total = 0;
        if (colors == null) {
            return total;
        }
        for (BlueprintColorDisplayItem color : colors) {
            if (color != null) {
                total += color.quantityPerBuild;
            }
        }
        return total;
    }

    private boolean bindFromViewModel() {
        for (BeadInventoryViewModel.BeadBlueprintItem item : viewModel.getBlueprintList()) {
            if (item.blueprintId == blueprintId) {
                bindItem(item);
                return true;
            }
        }
        return false;
    }

    private void loadDetail() {
        if (blueprintId <= 0) {
            return;
        }
        BeadUtils.getBlueprintDetail(requireContext(), blueprintId, new BeadUtils.BeadBlueprintDetailLoadCallback() {
            @Override public void onSuccess(AuthApiModels.BeadBlueprintResponse response) {
                if (response != null && response.data != null) {
                    bindItem(fromApi(response.data));
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bindItem(BeadInventoryViewModel.BeadBlueprintItem item) {
        currentItem = item;
        tvTitle.setText(item.name == null || item.name.trim().isEmpty() ? "未命名图纸" : item.name);
        List<BlueprintColorDisplayItem> colors = toDisplayColors(item);
        int total = item.totalBeadsPerBuild != null ? item.totalBeadsPerBuild : calculateTotalBeadsPerBuild(colors);
        tvTotal.setText(String.format(Locale.getDefault(), "每次 %d 颗", total));
        tvBuilds.setText(String.format(Locale.getDefault(), "已制作 %d 次", item.buildCount));
        adapter.submitList(colors);
    }

    private void buildOnce() {
        if (currentItem == null) {
            return;
        }
        BeadUtils.buildBlueprint(requireContext(), currentItem.blueprintId, 1, new BeadUtils.BuildBeadBlueprintCallback() {
            @Override public void onSuccess(AuthApiModels.BuildBeadBlueprintResponse response) {
                viewModel.loadBlueprints();
                loadDetail();
            }
            @Override public void onError(String error) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private List<BlueprintColorDisplayItem> toDisplayColors(BeadInventoryViewModel.BeadBlueprintItem item) {
        List<BlueprintColorDisplayItem> colors = new ArrayList<>();
        for (BeadInventoryViewModel.BeadBlueprintColor color : item.colors) {
            colors.add(new BlueprintColorDisplayItem(
                    color.colorCode,
                    color.hexColor,
                    color.quantityPerBuild,
                    color.totalConsumed == null ? item.buildCount * color.quantityPerBuild : color.totalConsumed,
                    color.isTransparent
            ));
        }
        return colors;
    }

    private BeadInventoryViewModel.BeadBlueprintItem fromApi(AuthApiModels.BeadBlueprintItemData data) {
        BeadInventoryViewModel.BeadBlueprintItem item = new BeadInventoryViewModel.BeadBlueprintItem();
        item.blueprintId = data.blueprintId;
        item.userId = data.userId;
        item.relationshipId = data.relationshipId;
        item.name = data.name;
        item.buildCount = data.buildCount;
        item.colorCount = data.colorCount;
        item.totalBeadsPerBuild = data.totalBeadsPerBuild;
        item.totalConsumed = data.totalConsumed;
        item.createdAt = data.createdAt;
        item.updatedAt = data.updatedAt;
        if (data.colors != null) {
            for (AuthApiModels.BeadBlueprintColorData colorData : data.colors) {
                BeadInventoryViewModel.BeadBlueprintColor color = new BeadInventoryViewModel.BeadBlueprintColor();
                color.colorCode = colorData.colorCode;
                color.hexColor = colorData.hexColor;
                color.colorGroup = colorData.colorGroup;
                color.isTransparent = colorData.isTransparent;
                color.quantityPerBuild = colorData.quantityPerBuild;
                color.totalConsumed = colorData.totalConsumed;
                item.colors.add(color);
            }
        }
        return item;
    }
}
