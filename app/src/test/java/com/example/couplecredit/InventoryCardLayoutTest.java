package com.example.couplecredit;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class InventoryCardLayoutTest {

    @Test
    public void inventoryCardLayout_placesNameQuantityAndCategoryOnSameRow() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        int titleRowStart = xml.indexOf("android:id=\"@+id/layout_title_row\"");
        int titleRowEnd = xml.indexOf("</LinearLayout>", titleRowStart);
        String titleRow = xml.substring(titleRowStart, titleRowEnd);

        assertTrue(titleRow.contains("android:id=\"@+id/tv_inventory_name\""));
        assertTrue(titleRow.contains("android:id=\"@+id/tv_quantity\""));
        assertTrue(titleRow.contains("android:id=\"@+id/tv_inventory_category\""));
    }

    @Test
    public void inventoryCardLayout_usesDenserCardSpacing() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:padding=\"8dp\""));
        assertTrue(xml.contains("android:paddingTop=\"4dp\""));
    }

    @Test
    public void inventoryCardLayout_usesLargerCenteredImage() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:gravity=\"center_vertical\""));
        assertTrue(xml.contains("android:layout_width=\"60dp\""));
        assertTrue(xml.contains("android:layout_height=\"60dp\""));
    }

    @Test
    public void inventoryHeaderLayout_usesCompactTopSpacing() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/fragment_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:paddingStart=\"16dp\""));
        assertTrue(xml.contains("android:paddingTop=\"16dp\""));
        assertTrue(xml.contains("android:paddingBottom=\"8dp\""));
        assertTrue(xml.contains("android:layout_marginTop=\"8dp\""));
        assertTrue(xml.contains("android:layout_height=\"40dp\""));
    }
}
