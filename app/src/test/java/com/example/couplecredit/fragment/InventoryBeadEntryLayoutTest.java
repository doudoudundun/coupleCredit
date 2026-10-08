package com.example.couplecredit.fragment;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class InventoryBeadEntryLayoutTest {

    @Test
    public void fragmentInventoryExposesClickableBeadInventoryEntry() throws IOException {
        String fragmentXml = new String(Files.readAllBytes(Path.of("src/main/res/layout/fragment_inventory.xml")), StandardCharsets.UTF_8);
        String entryXml = new String(Files.readAllBytes(Path.of("src/main/res/layout/item_bead_entry.xml")), StandardCharsets.UTF_8);
        String fragmentSource = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/example/couplecredit/fragment/InventoryFragment.java")), StandardCharsets.UTF_8);

        assertTrue(fragmentXml.contains("@+id/btn_bead_entry"));
        assertTrue(fragmentXml.contains("拼豆库存"));
        assertTrue(fragmentSource.contains("btnBeadEntry.setOnClickListener"));
        assertTrue(entryXml.contains("@+id/card_bead_inventory"));
        assertTrue(entryXml.contains("拼豆库存"));
        assertTrue(entryXml.contains("@+id/tv_bead_inventory_summary"));
        assertTrue(entryXml.contains("@drawable/ic_add"));
    }
}
