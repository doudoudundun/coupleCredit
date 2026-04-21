package com.example.couplecredit.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.InventoryUtils;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;

public class InventoryViewModel extends AndroidViewModel {

    public static class InventoryItem {
        public int id;
        public int userId;
        public Integer relationshipId;
        public String name;
        public String category;
        public String imageUrl;
        public double quantity;
        public String unit;
        public double threshold;
        public String expirationMode;
        public String expirationDate;
        public String productionDate;
        public Integer shelfLifeDays;
        public String createdAt;
        public String updatedAt;
        public String lastConsumedAt;
        public String note;
        public String aiImagePrompt;
        public boolean isLowStock;
        public boolean isExpiring;
        public boolean isExpired;
        public String lastActionLabel;

        public boolean isLowStock() {
            return isLowStock || quantity <= threshold;
        }
    }

    private final List<InventoryItem> inventoryList = new ArrayList<>();
    private final List<InventoryItem> lowStockList = new ArrayList<>();
    private final List<InventoryItem> recentActivityList = new ArrayList<>();
    private Integer cachedRelationshipId = null;

    private final MutableLiveData<Integer> dataVersion = new MutableLiveData<>(0);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);

    public InventoryViewModel(@NonNull Application application) {
        super(application);
    }

    public List<InventoryItem> getInventoryList() { return inventoryList; }
    public List<InventoryItem> getLowStockList() { return lowStockList; }
    public List<InventoryItem> getRecentActivityList() { return recentActivityList; }
    public Integer getCachedRelationshipId() { return cachedRelationshipId; }

    public LiveData<Integer> getDataVersion() { return dataVersion; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<Boolean> getLoading() { return loading; }

    public boolean hasData() { return !inventoryList.isEmpty(); }

    public void clearError() { errorMessage.setValue(null); }

    public void loadData() {
        if (!UserInfoManager.isUserLoggedIn(getApplication())) {
            inventoryList.clear();
            lowStockList.clear();
            recentActivityList.clear();
            dataVersion.postValue(dataVersion.getValue() + 1);
            return;
        }

        loading.postValue(true);
        InventoryUtils.loadInventory(getApplication(), new InventoryUtils.InventoryLoadCallback() {
            @Override
            public void onSuccess(AuthApiModels.InventoryListResponse response) {
                inventoryList.clear();
                lowStockList.clear();
                recentActivityList.clear();

                if (response != null && response.data != null) {
                    cachedRelationshipId = response.data.relationshipId;
                    if (response.data.items != null) {
                        for (AuthApiModels.InventoryItemData d : response.data.items) {
                            InventoryItem item = fromApi(d);
                            inventoryList.add(item);
                            if (item.isLowStock()) lowStockList.add(item);
                            recentActivityList.add(item);
                        }
                    }
                }

                if (recentActivityList.size() > 8) {
                    recentActivityList.subList(8, recentActivityList.size()).clear();
                }

                loading.postValue(false);
                dataVersion.postValue(dataVersion.getValue() + 1);
            }

            @Override
            public void onError(String error) {
                loading.postValue(false);
                errorMessage.postValue(error);
            }
        });
    }

    private InventoryItem fromApi(AuthApiModels.InventoryItemData d) {
        InventoryItem item = new InventoryItem();
        item.id = d.inventoryId;
        item.userId = d.userId;
        item.relationshipId = d.relationshipId;
        item.name = d.name;
        item.category = d.category;
        item.imageUrl = d.imageUrl;
        item.quantity = d.quantity;
        item.unit = d.unit;
        item.threshold = d.threshold;
        item.expirationMode = d.expirationMode;
        item.expirationDate = d.expirationDate;
        item.productionDate = d.productionDate;
        item.shelfLifeDays = d.shelfLifeDays;
        item.createdAt = normalizeDateTime(d.createdAt);
        item.updatedAt = normalizeDateTime(d.updatedAt);
        item.lastConsumedAt = normalizeDateTime(d.lastConsumedAt);
        item.note = d.note;
        item.aiImagePrompt = d.aiImagePrompt;
        item.isLowStock = item.quantity <= item.threshold;
        item.isExpiring = d.isExpiring;
        item.isExpired = d.isExpired;
        item.lastActionLabel = resolveActionLabel(item);
        return item;
    }

    private String normalizeDateTime(String value) {
        if (value == null) return null;
        String n = value.replace('T', ' ');
        int dot = n.indexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n;
    }

    private String resolveActionLabel(InventoryItem item) {
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) return "消耗";
        if (item.updatedAt != null && item.createdAt != null && !item.updatedAt.equals(item.createdAt)) return "更新";
        return "新增";
    }
}
