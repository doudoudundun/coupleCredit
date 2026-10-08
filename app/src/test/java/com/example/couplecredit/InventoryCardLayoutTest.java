package com.example.couplecredit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class InventoryCardLayoutTest {

    @Test
    public void inventoryCardLayout_keepsTitleActionsAndMetadataReadable() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        String fullCard = extractElement(xml, "layout_inventory_card", "FrameLayout");
        String titleRow = extractElement(fullCard, "layout_title_row", "LinearLayout");

        assertTrue(titleRow.contains("android:id=\"@+id/tv_inventory_name\""));
        assertTrue(fullCard.contains("android:id=\"@+id/btn_more\""));
        assertTrue(fullCard.contains("android:id=\"@+id/tv_quantity\""));
        assertTrue(fullCard.contains("android:id=\"@+id/tv_inventory_category\""));
        assertTrue(fullCard.contains("android:maxLines=\"1\""));
    }

    @Test
    public void inventoryCardLayout_usesCompactSingleColumnCardGeometry() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:layout_width=\"match_parent\""));
        assertTrue(xml.contains("android:layout_height=\"88dp\""));
        assertTrue(xml.contains("android:minHeight=\"32dp\""));
        assertTrue(xml.contains("android:layout_margin=\"4dp\""));
        assertTrue(xml.contains("android:paddingStart=\"8dp\""));
        assertTrue(xml.contains("android:paddingEnd=\"8dp\""));
        assertTrue(xml.contains("android:paddingBottom=\"24dp\""));
        assertTrue(xml.contains("android:layout_height=\"match_parent\""));

        String fullOpeningTag = extractOpeningTag(xml, "layout_inventory_card", "FrameLayout");
        assertTrue(fullOpeningTag.contains("android:layout_height=\"88dp\""));
        assertFalse(fullOpeningTag.contains("android:layout_height=\"match_parent\""));
    }

    @Test
    public void inventoryCardLayout_usesCompactCenteredImage() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:gravity=\"center_vertical\""));
        assertTrue(xml.contains("android:layout_width=\"56dp\""));
        assertTrue(xml.contains("android:layout_height=\"56dp\""));
    }

    @Test
    public void inventoryCardLayout_reservesFixedBottomSummaryForDeckReveal() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:layout_height=\"88dp\""));
        assertTrue(xml.contains("android:layout_margin=\"4dp\""));
        assertTrue(xml.contains("android:id=\"@+id/layout_inventory_summary\""));
        assertTrue(xml.contains("android:layout_gravity=\"bottom\""));
        assertTrue(xml.contains("android:layout_height=\"24dp\""));
        String fullCard = extractElement(xml, "layout_inventory_card", "FrameLayout");
        String summary = extractElement(fullCard, "layout_inventory_summary", "LinearLayout");
        assertTrue(summary.contains("android:id=\"@+id/layout_title_row\""));
        assertTrue(summary.contains("android:id=\"@+id/tv_inventory_name\""));
        assertTrue(summary.contains("android:textSize=\"13sp\""));
        assertTrue(summary.contains("android:minLines=\"1\""));
        assertTrue(summary.contains("android:id=\"@+id/tv_quantity\""));
        assertTrue(summary.contains("android:id=\"@+id/tv_low_stock_badge\""));
        assertTrue(summary.contains("android:orientation=\"horizontal\""));
        assertEquals(1, countOccurrences(summary, "android:id=\"@+id/tv_inventory_name\""));
        assertEquals(1, countOccurrences(summary, "android:id=\"@+id/tv_quantity\""));
    }

    @Test
    public void inventoryCardLayout_compactLayerContainsNameQuantityAndGreenMarker() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        String compactOpeningTag = extractOpeningTag(xml, "layout_inventory_compact", "LinearLayout");
        String compact = extractElement(xml, "layout_inventory_compact", "LinearLayout");

        assertTrue(compactOpeningTag.contains("android:layout_width=\"match_parent\""));
        assertTrue(compactOpeningTag.contains("android:layout_height=\"match_parent\""));
        assertTrue(compactOpeningTag.contains("android:gravity=\"center_vertical\""));
        assertTrue(compactOpeningTag.contains("android:alpha=\"0\""));
        assertTrue(compactOpeningTag.contains(
                "android:importantForAccessibility=\"noHideDescendants\""));
        assertTrue(compactOpeningTag.contains("android:paddingStart=\"8dp\""));
        assertTrue(compactOpeningTag.contains("android:paddingEnd=\"12dp\""));

        assertTrue(compact.contains("android:id=\"@+id/tv_inventory_compact_name\""));
        assertTrue(compact.contains("android:id=\"@+id/tv_inventory_compact_quantity\""));
        assertEquals(4, countOccurrences(compact, "android:id=\"@+id/"));
        assertEquals(2, countOccurrences(compact, "android:textSize=\"12sp\""));
        String marker = extractOpeningTag(xml, "inventory_compact_marker", "View");
        assertTrue(marker.contains("android:layout_width=\"3dp\""));
        assertTrue(marker.contains("android:layout_height=\"16dp\""));
        assertTrue(marker.contains("android:background=\"@color/brand_green\""));
        assertFalse(compact.contains("tv_inventory_name"));
        assertFalse(compact.contains("iv_inventory_image"));
        assertFalse(compact.contains("btn_consume"));
        assertFalse(compact.contains("btn_replenish"));
        assertFalse(compact.contains("btn_more"));
        assertFalse(compact.contains("tv_low_stock_badge"));
    }

    @Test
    public void inventoryCardLayout_keepsOptionalRowsAtStableHeights() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/item_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertTrue(xml.contains("android:id=\"@+id/layout_inventory_status_row\""));
        assertTrue(xml.contains("android:layout_height=\"12dp\""));
        assertTrue(xml.contains("android:id=\"@+id/tv_expiration_status\""));
        assertTrue(xml.contains("android:id=\"@+id/tv_note\""));
        assertTrue(xml.contains("android:visibility=\"gone\""));
        assertTrue(xml.contains("android:visibility=\"invisible\""));
        assertTrue(xml.contains("android:importantForAccessibility=\"yes\""));
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static String extractOpeningTag(String xml, String id, String tag) {
        String marker = "android:id=\"@+id/" + id + "\"";
        int idStart = xml.indexOf(marker);
        assertTrue("missing " + id, idStart >= 0);
        int elementStart = xml.lastIndexOf("<" + tag, idStart);
        int tagEnd = xml.indexOf(">", idStart);
        assertTrue("missing opening " + tag + " for " + id, elementStart >= 0 && tagEnd >= 0);
        return xml.substring(elementStart, tagEnd + 1);
    }

    private static String extractElement(String xml, String id, String tag) {
        String marker = "android:id=\"@+id/" + id + "\"";
        int idStart = xml.indexOf(marker);
        assertTrue("missing " + id, idStart >= 0);
        int elementStart = xml.lastIndexOf("<" + tag, idStart);
        assertTrue("missing opening " + tag + " for " + id, elementStart >= 0);

        String openToken = "<" + tag;
        String closeToken = "</" + tag + ">";
        int depth = 0;
        int cursor = elementStart;
        while (cursor < xml.length()) {
            int nextOpen = xml.indexOf(openToken, cursor);
            int nextClose = xml.indexOf(closeToken, cursor);
            if (nextClose < 0) {
                break;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                cursor = nextOpen + openToken.length();
            } else {
                depth--;
                if (depth == 0) {
                    return xml.substring(elementStart, nextClose + closeToken.length());
                }
                cursor = nextClose + closeToken.length();
            }
        }

        assertTrue("missing closing " + tag + " for " + id, false);
        return "";
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

    @Test
    public void inventoryLayout_floatsActionsWithoutFooterStrip() throws Exception {
        String xml = new String(
                Files.readAllBytes(Paths.get("src/main/res/layout/fragment_inventory.xml")),
                StandardCharsets.UTF_8
        );

        assertFalse(xml.contains("inventory_action_bar"));
        assertFalse(xml.contains("tv_inventory_deck_hint"));
        assertFalse(xml.contains("android:layout_marginBottom=\"72dp\""));
        assertTrue(countOccurrences(xml, "android:layout_gravity=\"bottom|end\"") == 2);
        assertTrue(xml.contains("android:id=\"@+id/fab_refresh_inventory\""));
        assertTrue(xml.contains("android:id=\"@+id/fab_add_inventory\""));
    }
}
