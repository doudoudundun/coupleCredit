package com.example.couplecredit.fragment;

import com.example.couplecredit.api.AuthApiModels;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class BeadBlueprintLogicTest {

    @Test
    public void buildColorRequestsNormalizesCodesAndSkipsInvalidRows() {
        List<BeadBlueprintListFragment.BlueprintColorDraft> drafts = Arrays.asList(
                new BeadBlueprintListFragment.BlueprintColorDraft(" a1 ", "12"),
                new BeadBlueprintListFragment.BlueprintColorDraft("", "5"),
                new BeadBlueprintListFragment.BlueprintColorDraft("m15", "0"),
                new BeadBlueprintListFragment.BlueprintColorDraft("c2", "8")
        );

        List<AuthApiModels.BeadBlueprintColorRequest> requests = BeadBlueprintListFragment.buildColorRequests(drafts);

        assertEquals(2, requests.size());
        assertEquals("A01", requests.get(0).colorCode);
        assertEquals(12, requests.get(0).quantityPerBuild);
        assertEquals("C02", requests.get(1).colorCode);
        assertEquals(8, requests.get(1).quantityPerBuild);
    }

    @Test
    public void calculateTotalBeadsPerBuildSumsBlueprintColors() {
        List<BeadBlueprintDetailFragment.BlueprintColorDisplayItem> colors = Arrays.asList(
                new BeadBlueprintDetailFragment.BlueprintColorDisplayItem("A01", "#faf5cd", 12, 40, false),
                new BeadBlueprintDetailFragment.BlueprintColorDisplayItem("C02", "#A9F9FC", 8, 10, true)
        );

        assertEquals(20, BeadBlueprintDetailFragment.calculateTotalBeadsPerBuild(colors));
    }
}
