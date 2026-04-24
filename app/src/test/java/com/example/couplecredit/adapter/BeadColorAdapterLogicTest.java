package com.example.couplecredit.adapter;

import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BeadColorAdapterLogicTest {

    @Test
    public void buildDisplayItemsUsesServerInventoryRowsAndMarksLowStockEntries() {
        List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems = new ArrayList<>();

        BeadInventoryViewModel.BeadInventoryItem lowStock = new BeadInventoryViewModel.BeadInventoryItem();
        lowStock.colorCode = "A01";
        lowStock.hexColor = "#faf5cd";
        lowStock.quantity = 12;
        lowStock.defaultThreshold = 20;
        lowStock.isLowStock = true;
        inventoryItems.add(lowStock);

        BeadInventoryViewModel.BeadInventoryItem healthy = new BeadInventoryViewModel.BeadInventoryItem();
        healthy.colorCode = "M15";
        healthy.hexColor = "#757D78";
        healthy.quantity = 200;
        healthy.defaultThreshold = 20;
        healthy.isLowStock = false;
        inventoryItems.add(healthy);

        List<BeadColorAdapter.BeadColorDisplayItem> displayItems = BeadColorAdapter.buildDisplayItems(inventoryItems, "", false);

        assertEquals(2, displayItems.size());
        assertEquals("A01", displayItems.get(0).colorCode);
        assertEquals("M15", displayItems.get(1).colorCode);
        assertTrue(displayItems.get(0).isLowStock);
    }

    @Test
    public void buildDisplayItemsAppliesSearchAndLowStockFilters() {
        List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems = new ArrayList<>();

        BeadInventoryViewModel.BeadInventoryItem lowStock = new BeadInventoryViewModel.BeadInventoryItem();
        lowStock.colorCode = "A10";
        lowStock.hexColor = "#F77C31";
        lowStock.quantity = 3;
        lowStock.defaultThreshold = 10;
        lowStock.isLowStock = true;
        inventoryItems.add(lowStock);

        BeadInventoryViewModel.BeadInventoryItem healthy = new BeadInventoryViewModel.BeadInventoryItem();
        healthy.colorCode = "A11";
        healthy.hexColor = "#FFDD99";
        healthy.quantity = 30;
        healthy.defaultThreshold = 10;
        healthy.isLowStock = false;
        inventoryItems.add(healthy);

        List<BeadColorAdapter.BeadColorDisplayItem> filtered = BeadColorAdapter.buildDisplayItems(inventoryItems, " a10 ", true);

        assertEquals(1, filtered.size());
        assertEquals("A10", filtered.get(0).colorCode);
    }

    @Test
    public void buildDisplayItemsNormalizesSingleDigitSearchToMardCodeFormat() {
        List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems = new ArrayList<>();

        BeadInventoryViewModel.BeadInventoryItem item = new BeadInventoryViewModel.BeadInventoryItem();
        item.colorCode = "M01";
        item.hexColor = "#BCC6B8";
        item.quantity = 20;
        item.defaultThreshold = 10;
        inventoryItems.add(item);

        List<BeadColorAdapter.BeadColorDisplayItem> filtered = BeadColorAdapter.buildDisplayItems(inventoryItems, "m1", false);

        assertEquals(1, filtered.size());
        assertEquals("M01", filtered.get(0).colorCode);
    }
}
