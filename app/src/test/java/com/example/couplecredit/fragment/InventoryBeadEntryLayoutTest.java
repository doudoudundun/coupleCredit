package com.example.couplecredit.fragment;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class InventoryBeadEntryLayoutTest {

    @Test
    public void fragmentInventoryIncludesBeadInventoryEntryCard() throws IOException {
        String xml = new String(Files.readAllBytes(Path.of("src/main/res/layout/fragment_inventory.xml")), StandardCharsets.UTF_8);

        assertTrue(xml.contains("@+id/card_bead_inventory"));
        assertTrue(xml.contains("拼豆库存"));
        assertTrue(xml.contains("@+id/tv_bead_inventory_summary"));
        assertTrue(xml.contains("@drawable/ic_add"));
    }
}
