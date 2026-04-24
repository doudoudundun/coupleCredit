package com.example.couplecredit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FloatingRefreshLayoutTest {

    @Test
    public void classicPage_doesNotHaveFloatingRefreshButton() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/classic_model_fragment.xml")), StandardCharsets.UTF_8);
        assertFalse(xml.contains("@+id/fab_refresh_bill"));
    }

    @Test
    public void inventoryPage_hasFloatingRefreshButton() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/fragment_inventory.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("@+id/fab_refresh_inventory"));
        assertTrue(xml.contains("app:fabSize=\"mini\""));
    }

    @Test
    public void recipePage_hasFloatingRefreshButton() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/fragment_recipe.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("@+id/fab_refresh_recipe"));
        assertTrue(xml.contains("app:fabSize=\"mini\""));
        assertTrue(xml.contains("@+id/fab_add_recipe"));
        assertTrue(xml.contains("android:paddingBottom=\"136dp\""));
    }

    @Test
    public void todoPage_hasFloatingRefreshButton() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/fragment_todo.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("@+id/fab_refresh_todo"));
        assertTrue(xml.contains("app:fabSize=\"mini\""));
        assertTrue(xml.contains("android:paddingBottom=\"136dp\""));
    }

    @Test
    public void sharedPlansPage_hasFloatingRefreshButton() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/fragment_shared_plans.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("@+id/fab_refresh_plan"));
        assertTrue(xml.contains("app:fabSize=\"mini\""));
    }

    @Test
    public void reportPage_hasCompactRefreshLayout() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/fragment_report.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("@+id/fab_refresh_report"));
        assertTrue(xml.contains("app:fabSize=\"mini\""));
        assertTrue(xml.contains("android:paddingBottom=\"64dp\""));
    }

    @Test
    public void reportPage_allowsContentToRenderInsideBottomInset() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/layout/fragment_report.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("android:clipToPadding=\"false\""));
    }
}
