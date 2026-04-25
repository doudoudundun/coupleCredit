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

    @Test
    public void buildDisplayItemsFiltersByColorGroup() {
        List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems = new ArrayList<>();

        BeadInventoryViewModel.BeadInventoryItem a1 = new BeadInventoryViewModel.BeadInventoryItem();
        a1.colorCode = "A01";
        a1.hexColor = "#faf5cd";
        a1.colorGroup = "A";
        a1.quantity = 50;
        a1.defaultThreshold = 20;
        inventoryItems.add(a1);

        BeadInventoryViewModel.BeadInventoryItem b1 = new BeadInventoryViewModel.BeadInventoryItem();
        b1.colorCode = "B01";
        b1.hexColor = "#E6EE31";
        b1.colorGroup = "B";
        b1.quantity = 100;
        b1.defaultThreshold = 20;
        inventoryItems.add(b1);

        List<BeadColorAdapter.BeadColorDisplayItem> filtered = BeadColorAdapter.buildDisplayItems(inventoryItems, "", false, "A");
        assertEquals(1, filtered.size());
        assertEquals("A01", filtered.get(0).colorCode);
    }

    @Test
    public void buildDisplayItemsNullGroupReturnsAll() {
        List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems = new ArrayList<>();

        BeadInventoryViewModel.BeadInventoryItem a1 = new BeadInventoryViewModel.BeadInventoryItem();
        a1.colorCode = "A01";
        a1.colorGroup = "A";
        a1.quantity = 50;
        a1.defaultThreshold = 20;
        inventoryItems.add(a1);

        BeadInventoryViewModel.BeadInventoryItem b1 = new BeadInventoryViewModel.BeadInventoryItem();
        b1.colorCode = "B01";
        b1.colorGroup = "B";
        b1.quantity = 100;
        b1.defaultThreshold = 20;
        inventoryItems.add(b1);

        List<BeadColorAdapter.BeadColorDisplayItem> filtered = BeadColorAdapter.buildDisplayItems(inventoryItems, "", false, null);
        assertEquals(2, filtered.size());
    }

    @Test
    public void buildDisplayItemsCombinesGroupWithLowStockFilter() {
        List<BeadInventoryViewModel.BeadInventoryItem> inventoryItems = new ArrayList<>();

        BeadInventoryViewModel.BeadInventoryItem a1 = new BeadInventoryViewModel.BeadInventoryItem();
        a1.colorCode = "A01";
        a1.colorGroup = "A";
        a1.quantity = 5;
        a1.defaultThreshold = 20;
        a1.isLowStock = true;
        inventoryItems.add(a1);

        BeadInventoryViewModel.BeadInventoryItem a2 = new BeadInventoryViewModel.BeadInventoryItem();
        a2.colorCode = "A02";
        a2.colorGroup = "A";
        a2.quantity = 200;
        a2.defaultThreshold = 20;
        a2.isLowStock = false;
        inventoryItems.add(a2);

        BeadInventoryViewModel.BeadInventoryItem b1 = new BeadInventoryViewModel.BeadInventoryItem();
        b1.colorCode = "B01";
        b1.colorGroup = "B";
        b1.quantity = 3;
        b1.defaultThreshold = 20;
        b1.isLowStock = true;
        inventoryItems.add(b1);

        List<BeadColorAdapter.BeadColorDisplayItem> filtered = BeadColorAdapter.buildDisplayItems(inventoryItems, "", true, "A");
        assertEquals(1, filtered.size());
        assertEquals("A01", filtered.get(0).colorCode);
    }
}
