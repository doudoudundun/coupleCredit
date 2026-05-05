package com.example.couplecredit.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.DataLocalCache;
import com.google.gson.Gson;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.DateTimeUtils;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;

public class BeadInventoryViewModel extends AndroidViewModel {

    public static class BeadInventoryItem {
        public String colorCode;
        public String hexColor;
        public int quantity;
        public Integer thresholdOverride;
        public int defaultThreshold;
        public int totalConsumed;
        public String colorGroup;
        public boolean isTransparent;
        public boolean isLowStock;

        public int getEffectiveThreshold() {
            return thresholdOverride != null ? thresholdOverride : defaultThreshold;
        }

        public boolean isLowStock() {
            return isLowStock || quantity <= getEffectiveThreshold();
        }
    }

    public static class BeadSummary {
        public int totalColors;
        public int lowStockCount;
        public int totalConsumptionReference;
    }

    public static class BeadBlueprintColor {
        public String colorCode;
        public String hexColor;
        public String colorGroup;
        public boolean isTransparent;
        public int quantityPerBuild;
        public Integer totalConsumed;
    }

    public static class BeadBlueprintItem {
        public int blueprintId;
        public int userId;
        public Integer relationshipId;
        public boolean isPartner;
        public String name;
        public String imageUrl;
        public int buildCount;
        public Integer colorCount;
        public Integer totalBeadsPerBuild;
        public Integer totalConsumed;
        public String createdAt;
        public String updatedAt;
        public final List<BeadBlueprintColor> colors = new ArrayList<>();
    }

    private final List<BeadInventoryItem> inventoryList = new ArrayList<>();
    private final List<BeadBlueprintItem> blueprintList = new ArrayList<>();
    private final BeadSummary summary = new BeadSummary();
    private Integer cachedRelationshipId = null;
    private boolean inventoryLoaded = false;
    private boolean blueprintsLoaded = false;
    private int activeRequests = 0;

    private final MutableLiveData<Integer> dataVersion = new MutableLiveData<>(0);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private static final String CACHE_INVENTORY = "bead_inventory_";
    private static final String CACHE_BLUEPRINTS = "bead_blueprints_";
    private final Gson gson = new Gson();

    public BeadInventoryViewModel(@NonNull Application application) {
        super(application);
    }

    public List<BeadInventoryItem> getInventoryList() { return inventoryList; }
    public List<BeadBlueprintItem> getBlueprintList() { return blueprintList; }
    public BeadSummary getSummary() { return summary; }
    public Integer getCachedRelationshipId() { return cachedRelationshipId; }

    public LiveData<Integer> getDataVersion() { return dataVersion; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<Boolean> getLoading() { return loading; }

    public boolean hasData() {
        return inventoryLoaded || blueprintsLoaded;
    }

    public boolean hasInventoryData() {
        return inventoryLoaded;
    }

    public boolean hasBlueprintData() {
        return blueprintsLoaded;
    }

    public void clearError() {
        errorMessage.setValue(null);
    }

    public void loadData() {
        if (!UserInfoManager.isUserLoggedIn(getApplication())) {
            clearAllData();
            return;
        }

        clearError();
        loadInventory();
        loadBlueprints();
    }

    public void refresh() {
        loadData();
    }

    public void loadInventory() {
        if (!UserInfoManager.isUserLoggedIn(getApplication())) {
            clearAllData();
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(getApplication());

        if (!inventoryLoaded) {
            String cached = DataLocalCache.get(getApplication(), CACHE_INVENTORY + userId);
            if (cached != null) {
                try {
                    AuthApiModels.BeadInventoryListResponse r = gson.fromJson(cached, AuthApiModels.BeadInventoryListResponse.class);
                    if (r != null) applyInventoryResponse(r);
                } catch (Exception ignored) {}
            }
        }

        beginLoading();
        BeadUtils.loadInventory(getApplication(), new BeadUtils.BeadInventoryLoadCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadInventoryListResponse response) {
                applyInventoryResponse(response);
                DataLocalCache.put(getApplication(), CACHE_INVENTORY + userId, gson.toJson(response));
                inventoryLoaded = true;
                endLoading();
                bumpVersion();
            }

            @Override
            public void onError(String error) {
                endLoading();
                errorMessage.postValue(error);
            }
        });
    }

    public void loadBlueprints() {
        if (!UserInfoManager.isUserLoggedIn(getApplication())) {
            clearAllData();
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(getApplication());

        if (!blueprintsLoaded) {
            String cached = DataLocalCache.get(getApplication(), CACHE_BLUEPRINTS + userId);
            if (cached != null) {
                try {
                    AuthApiModels.BeadBlueprintListResponse r = gson.fromJson(cached, AuthApiModels.BeadBlueprintListResponse.class);
                    if (r != null) applyBlueprintResponse(r);
                } catch (Exception ignored) {}
            }
        }

        beginLoading();
        BeadUtils.loadBlueprints(getApplication(), new BeadUtils.BeadBlueprintListLoadCallback() {
            @Override
            public void onSuccess(AuthApiModels.BeadBlueprintListResponse response) {
                applyBlueprintResponse(response);
                DataLocalCache.put(getApplication(), CACHE_BLUEPRINTS + userId, gson.toJson(response));
                blueprintsLoaded = true;
                endLoading();
                bumpVersion();
            }

            @Override
            public void onError(String error) {
                endLoading();
                errorMessage.postValue(error);
            }
        });
    }

    private void applyInventoryResponse(AuthApiModels.BeadInventoryListResponse response) {
        inventoryList.clear();
        resetSummary();
        if (response != null && response.data != null) {
            cachedRelationshipId = response.data.relationshipId;
            UserInfoManager.saveRelationshipId(getApplication(), cachedRelationshipId);
            if (response.data.items != null) {
                for (AuthApiModels.BeadInventoryItemData itemData : response.data.items) {
                    inventoryList.add(fromInventoryApi(itemData));
                }
            }
            if (response.data.summary != null) {
                applySummary(response.data.summary);
            } else {
                deriveSummaryFromInventory();
            }
        }
    }

    private void applyBlueprintResponse(AuthApiModels.BeadBlueprintListResponse response) {
        blueprintList.clear();
        if (response != null && response.data != null && response.data.items != null) {
            for (AuthApiModels.BeadBlueprintItemData itemData : response.data.items) {
                blueprintList.add(fromBlueprintApi(itemData));
            }
        }
    }

    private synchronized void beginLoading() {
        activeRequests++;
        loading.postValue(true);
    }

    private synchronized void endLoading() {
        activeRequests = Math.max(0, activeRequests - 1);
        if (activeRequests == 0) {
            loading.postValue(false);
        }
    }

    private void clearAllData() {
        inventoryList.clear();
        blueprintList.clear();
        cachedRelationshipId = null;
        resetSummary();
        inventoryLoaded = false;
        blueprintsLoaded = false;
        UserInfoManager.saveRelationshipId(getApplication(), null);
        loading.postValue(false);
        activeRequests = 0;
        bumpVersion();
    }

    private void resetSummary() {
        summary.totalColors = 0;
        summary.lowStockCount = 0;
        summary.totalConsumptionReference = 0;
    }

    private void applySummary(AuthApiModels.BeadSummaryData data) {
        summary.totalColors = data.totalColors;
        summary.lowStockCount = data.lowStockCount;
        summary.totalConsumptionReference = data.totalConsumptionReference;
    }

    private void deriveSummaryFromInventory() {
        summary.totalColors = inventoryList.size();
        summary.lowStockCount = 0;
        summary.totalConsumptionReference = 0;
        for (BeadInventoryItem item : inventoryList) {
            if (item.isLowStock()) {
                summary.lowStockCount++;
            }
            summary.totalConsumptionReference += item.totalConsumed;
        }
    }

    private void bumpVersion() {
        Integer version = dataVersion.getValue();
        dataVersion.postValue(version == null ? 1 : version + 1);
    }

    private BeadInventoryItem fromInventoryApi(AuthApiModels.BeadInventoryItemData data) {
        BeadInventoryItem item = new BeadInventoryItem();
        item.colorCode = data.colorCode;
        item.hexColor = data.hexColor;
        item.quantity = data.quantity;
        item.thresholdOverride = data.thresholdOverride;
        item.defaultThreshold = data.defaultThreshold;
        item.totalConsumed = data.totalConsumed;
        item.colorGroup = data.colorGroup;
        item.isTransparent = data.isTransparent;
        item.isLowStock = data.isLowStock;
        return item;
    }

    private BeadBlueprintItem fromBlueprintApi(AuthApiModels.BeadBlueprintItemData data) {
        BeadBlueprintItem item = new BeadBlueprintItem();
        item.blueprintId = data.blueprintId;
        item.userId = data.userId;
        item.relationshipId = data.relationshipId;
        item.isPartner = data.isPartner;
        item.name = data.name;
        item.imageUrl = data.imageUrl;
        item.buildCount = data.buildCount;
        item.colorCount = data.colorCount;
        item.totalBeadsPerBuild = data.totalBeadsPerBuild;
        item.totalConsumed = data.totalConsumed;
        item.createdAt = normalizeDateTime(data.createdAt);
        item.updatedAt = normalizeDateTime(data.updatedAt);
        if (data.colors != null) {
            for (AuthApiModels.BeadBlueprintColorData colorData : data.colors) {
                item.colors.add(fromBlueprintColorApi(colorData));
            }
        }
        return item;
    }

    private BeadBlueprintColor fromBlueprintColorApi(AuthApiModels.BeadBlueprintColorData data) {
        BeadBlueprintColor color = new BeadBlueprintColor();
        color.colorCode = data.colorCode;
        color.hexColor = data.hexColor;
        color.colorGroup = data.colorGroup;
        color.isTransparent = data.isTransparent;
        color.quantityPerBuild = data.quantityPerBuild;
        color.totalConsumed = data.totalConsumed;
        return color;
    }

    private String normalizeDateTime(String value) {
        return DateTimeUtils.normalizeDateTimeToUtc8(value);
    }
}
