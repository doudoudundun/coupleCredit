package com.example.couplecredit.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InventoryDeckGeometryTest {

    private static final float EPSILON = 0.001f;
    private static final float OUTER_HEIGHT = 96f;
    private static final float VISUAL_PITCH = 96f;
    private static final float VIEWPORT_HEIGHT = 400f;

    @Test
    public void defaultsKeepFullCardsAndCompactRowsWithinTheIntendedScale() {
        assertEquals(96f, InventoryDeckGeometry.DEFAULT_ROW_PITCH_DP, EPSILON);
        assertTrue(InventoryDeckGeometry.DEFAULT_SCROLL_STEP_DP >= 100f);
        assertTrue(InventoryDeckGeometry.DEFAULT_SCROLL_STEP_DP <= 110f);
        assertEquals(9, InventoryDeckGeometry.DEFAULT_MAX_ATTACHED_ROWS);
        assertEquals(88f, InventoryDeckGeometry.FULL_ROOT_HEIGHT_DP, EPSILON);
        assertEquals(32f, InventoryDeckGeometry.COMPACT_ROOT_HEIGHT_DP, EPSILON);
        assertEquals(96f, InventoryDeckGeometry.EXPANDED_OUTER_HEIGHT_DP, EPSILON);
        assertEquals(40f, InventoryDeckGeometry.COMPACT_OUTER_HEIGHT_DP, EPSILON);
    }

    @Test
    public void rowOffsetClampsAtBothEndsAndHandlesDegenerateCounts() {
        assertEquals(0f, InventoryDeckGeometry.clampRowOffset(-2f, 5), 0f);
        assertEquals(4f, InventoryDeckGeometry.clampRowOffset(20f, 5), 0f);
        assertEquals(0f, InventoryDeckGeometry.clampRowOffset(Float.NaN, 5), 0f);
        assertEquals(0f, InventoryDeckGeometry.clampRowOffset(0.5f, 0), 0f);
        assertEquals(0f, InventoryDeckGeometry.clampRowOffset(0.5f, 1), 0f);
        assertEquals(0f, InventoryDeckGeometry.rowOffsetFromPixels(52f, 104f, 1), 0f);
        assertEquals(-1, InventoryDeckGeometry.focusedRow(0f, 0));
        assertEquals(0, InventoryDeckGeometry.focusedRow(0.49f, 3));
        assertEquals(1, InventoryDeckGeometry.focusedRow(0.5f, 3));
        assertEquals(2, InventoryDeckGeometry.focusedRow(10f, 3));
        assertEquals(0, InventoryDeckGeometry.focusedRow(0.5f, 1));
    }

    @Test
    public void focalAnchorUsesAConstantExpandedOuterHeight() {
        float anchor = InventoryDeckGeometry.focalAnchorTop(0f, VIEWPORT_HEIGHT, OUTER_HEIGHT);
        assertEquals(VIEWPORT_HEIGHT * 0.36f - OUTER_HEIGHT * 0.5f, anchor, EPSILON);
        assertEquals(144f, anchor + OUTER_HEIGHT * 0.5f, EPSILON);

        float moved = InventoryDeckGeometry.focalAnchorTop(0f, VIEWPORT_HEIGHT,
                InventoryDeckGeometry.actualOuterHeight(2f, OUTER_HEIGHT));
        assertTrue("the anchor helper reflects the supplied expanded height",
                moved > anchor);
    }

    @Test
    public void onlyLowerRowsCrossfadeAndShrinkAfterTheFullCardBand() {
        InventoryDeckGeometry.VisualState upper = InventoryDeckGeometry.visualState(-3f, 4);
        assertEquals(1f, upper.rootHeightFraction, EPSILON);
        assertEquals(1f, upper.fullContentAlpha, EPSILON);
        assertEquals(0f, upper.compactContentAlpha, EPSILON);

        InventoryDeckGeometry.VisualState near = InventoryDeckGeometry.visualState(1f, 4);
        assertEquals(1f, near.rootHeightFraction, EPSILON);
        assertEquals(1f, near.fullContentAlpha, EPSILON);
        assertEquals(0f, near.compactContentAlpha, EPSILON);

        InventoryDeckGeometry.VisualState crossfade = InventoryDeckGeometry.visualState(1.1f, 4);
        assertTrue(crossfade.fullContentAlpha > 0f && crossfade.fullContentAlpha < 1f);
        assertEquals(1f, crossfade.fullContentAlpha + crossfade.compactContentAlpha, EPSILON);
        assertEquals(1f, crossfade.rootHeightFraction, EPSILON);

        InventoryDeckGeometry.VisualState compactStart = InventoryDeckGeometry.visualState(1.2f, 4);
        assertEquals(0f, compactStart.fullContentAlpha, EPSILON);
        assertEquals(1f, compactStart.compactContentAlpha, EPSILON);
        assertEquals(1f, compactStart.rootHeightFraction, EPSILON);

        InventoryDeckGeometry.VisualState far = InventoryDeckGeometry.visualState(2f, 4);
        assertEquals(0f, far.fullContentAlpha, EPSILON);
        assertEquals(1f, far.compactContentAlpha, EPSILON);
        assertEquals(32f / 88f, far.rootHeightFraction, EPSILON);
        assertEquals(40f / 96f, far.outerHeightFraction, EPSILON);
    }

    @Test
    public void outwardWheelFlowsIntoTheOppositeLowerCurvature() {
        InventoryDeckGeometry.VisualState upper = InventoryDeckGeometry.visualState(-1f, 4);
        InventoryDeckGeometry.VisualState lower = InventoryDeckGeometry.visualState(1f, 4);
        assertEquals(12f, upper.rotationX, EPSILON);
        assertEquals(-12f, lower.rotationX, EPSILON);
        assertTrue("upper and lower rows must bend away from the center",
                upper.rotationX > 0f && lower.rotationX < 0f);

        assertTrue(InventoryDeckGeometry.visualState(2f, 4).rotationX < 0f);
        InventoryDeckGeometry.VisualState flat = InventoryDeckGeometry.visualState(2.2f, 4);
        assertEquals(0f, flat.rotationX, EPSILON);
        assertEquals(0.985f, flat.scale, EPSILON);
        assertEquals(1f, flat.scaleY, EPSILON);
        assertFalse(flat.shouldBlur);
        assertTrue(InventoryDeckGeometry.visualState(3f, 4).rotationX > 0f);
    }

    @Test
    public void farRowsAreWholeCompactStripsWithStableBaseAlpha() {
        InventoryDeckGeometry.VisualState far = InventoryDeckGeometry.visualState(3f, 4);
        assertEquals(32f / 88f, far.rootHeightFraction, EPSILON);
        assertEquals(40f / 96f, far.outerHeightFraction, EPSILON);
        assertTrue(far.scale >= 0.985f && far.scale <= 1f);
        assertEquals(1f, far.scaleY, EPSILON);
        assertTrue(far.rotationX > 0f && far.rotationX <= 12f);
        assertFalse(far.shouldBlur);
        assertTrue("far base alpha stays readable before edge fade",
                InventoryDeckGeometry.baseAlpha(3f) >= 0.92f);
        assertEquals(0f, InventoryDeckGeometry.visualState(4f, 4).alpha, EPSILON);
    }

    @Test
    public void bothWheelsJoinWithoutAngleOrVelocityJumps() {
        float h = 0.001f;
        for (float join : new float[]{-2f, -1f, 0f, 1f, 2f, 2.2f, 3.4f}) {
            InventoryDeckGeometry.VisualState left = InventoryDeckGeometry.visualState(join - h, 4);
            InventoryDeckGeometry.VisualState at = InventoryDeckGeometry.visualState(join, 4);
            InventoryDeckGeometry.VisualState right = InventoryDeckGeometry.visualState(join + h, 4);
            assertEquals("rotation velocity at " + join,
                    (at.rotationX - left.rotationX) / h,
                    (right.rotationX - at.rotationX) / h, 0.09f);
            assertEquals("width velocity at " + join,
                    (at.scale - left.scale) / h,
                    (right.scale - at.scale) / h, 0.002f);
        }
        float previous = -12f;
        for (int step = 1001; step <= 4000; step++) {
            InventoryDeckGeometry.VisualState state = InventoryDeckGeometry.visualState(step / 1000f, 4);
            assertTrue("lower tangent must not reverse during handoff", state.rotationX >= previous);
            assertTrue("no angle jump between adjacent drag samples", state.rotationX - previous < 0.016f);
            assertEquals("compact text is never vertically squashed", 1f, state.scaleY, 0f);
            previous = state.rotationX;
        }
    }

    @Test
    public void lowerProjectedCentersAndBothVisualEdgesRemainMonotonic() {
        float previousCenter = Float.NEGATIVE_INFINITY;
        float previousTop = Float.NEGATIVE_INFINITY;
        float previousBottom = Float.NEGATIVE_INFINITY;
        for (int step = 0; step <= 4000; step++) {
            float distance = step / 1000f;
            float center = InventoryDeckGeometry.projectedCenter(100f, 0, -distance,
                    VISUAL_PITCH, OUTER_HEIGHT);
            float top = InventoryDeckGeometry.rowTop(100f, 0, -distance,
                    VISUAL_PITCH, OUTER_HEIGHT);
            float bottom = top + InventoryDeckGeometry.actualRootHeight(distance, 88f);
            assertTrue("projected center must move down monotonically", center >= previousCenter);
            assertTrue("top edge must not reverse during collapse", top >= previousTop - EPSILON);
            assertTrue("bottom edge must not reverse during collapse",
                    bottom >= previousBottom - EPSILON);
            previousCenter = center;
            previousTop = top;
            previousBottom = bottom;
        }
    }

    @Test
    public void foldedRowsNeverOverlapAtFractionalScrollPhases() {
        for (int step = 0; step <= 3000; step++) {
            float offset = step / 1000f;
            float previousTop = InventoryDeckGeometry.rowTop(100f, 0, offset,
                    VISUAL_PITCH, OUTER_HEIGHT);
            float previousHeight = InventoryDeckGeometry.actualRootHeight(-offset, 88f);
            float previousBottom = previousTop + previousHeight;
            for (int row = 1; row <= 5; row++) {
                float distance = row - offset;
                float currentTop = InventoryDeckGeometry.rowTop(100f, row, offset,
                        VISUAL_PITCH, OUTER_HEIGHT);
                float currentHeight = InventoryDeckGeometry.actualRootHeight(distance, 88f);
                assertTrue("adjacent cards keep at least 1dp clearance at phase " + offset,
                        currentTop - previousBottom >= 1f);
                previousTop = currentTop;
                previousBottom = currentTop + currentHeight;
            }
        }
    }

    @Test
    public void fullToCompactJoinHasNoOversizedStaticGap() {
        float fullBottom = topAtDistance(100f, 1f) + 88f;
        float compactTop = topAtDistance(100f, 2f);
        assertTrue("S join should stay within 19dp", compactTop - fullBottom <= 19f);
        assertTrue("whole cards retain breathing room", compactTop - fullBottom >= 8f);
    }

    @Test
    public void projectionAndHeightTransitionsHaveContinuousDerivatives() {
        float h = 0.0001f;
        float pitchLeft = InventoryDeckGeometry.projectedDistance(
                InventoryDeckGeometry.PITCH_TRANSITION_START_DISTANCE - h,
                VISUAL_PITCH, OUTER_HEIGHT);
        float pitchAt = InventoryDeckGeometry.projectedDistance(
                InventoryDeckGeometry.PITCH_TRANSITION_START_DISTANCE,
                VISUAL_PITCH, OUTER_HEIGHT);
        float pitchRight = InventoryDeckGeometry.projectedDistance(
                InventoryDeckGeometry.PITCH_TRANSITION_START_DISTANCE + h,
                VISUAL_PITCH, OUTER_HEIGHT);
        assertEquals((pitchAt - pitchLeft) / h, (pitchRight - pitchAt) / h, 0.1f);

        float endLeft = InventoryDeckGeometry.actualRootHeight(
                InventoryDeckGeometry.SHRINK_END_DISTANCE - h, 88f);
        float endAt = InventoryDeckGeometry.actualRootHeight(
                InventoryDeckGeometry.SHRINK_END_DISTANCE, 88f);
        float endRight = InventoryDeckGeometry.actualRootHeight(
                InventoryDeckGeometry.SHRINK_END_DISTANCE + h, 88f);
        assertEquals((endAt - endLeft) / h, (endRight - endAt) / h, 0.1f);
        assertEquals(88f, InventoryDeckGeometry.actualRootHeight(-5f, 88f), EPSILON);
    }

    @Test
    public void edgeFadesHaveNoNearCenterNotchAndFadeBeforeLowerCut() {
        float anchor = InventoryDeckGeometry.focalAnchorTop(0f, VIEWPORT_HEIGHT, OUTER_HEIGHT);
        assertEquals(1f, InventoryDeckGeometry.topEdgeAlpha(
                topAtDistance(anchor, -1f), 0f, OUTER_HEIGHT, -1f), EPSILON);
        assertTrue(InventoryDeckGeometry.topEdgeAlpha(
                topAtDistance(anchor, -1.001f), 0f, OUTER_HEIGHT, -1.001f) > 0.99f);
        assertEquals(0f, InventoryDeckGeometry.topEdgeAlpha(
                topAtDistance(anchor, -2f), 0f, OUTER_HEIGHT, -2f), EPSILON);

        assertEquals(1f, InventoryDeckGeometry.bottomEdgeAlpha(120f, 150f, 40f, 0f), EPSILON);
        assertTrue("lower fade starts before its edge",
                InventoryDeckGeometry.bottomEdgeAlpha(95f, 150f, 40f, 1f) < 1f);
        assertEquals(0f, InventoryDeckGeometry.bottomEdgeAlpha(110f, 150f, 40f, 1f), EPSILON);
    }

    @Test
    public void shortViewportKeepsUpperHandoffContinuousAndFocusedCardOpaque() {
        float shortViewport = 150f;
        float anchor = InventoryDeckGeometry.focalAnchorTop(0f, shortViewport, OUTER_HEIGHT);
        float atBoundary = InventoryDeckGeometry.topEdgeAlpha(
                topAtDistance(anchor, -1f), 0f, OUTER_HEIGHT, -1f);
        float justBeyond = InventoryDeckGeometry.topEdgeAlpha(
                topAtDistance(anchor, -1.001f), 0f, OUTER_HEIGHT, -1.001f);
        assertEquals(1f, atBoundary, EPSILON);
        assertTrue("short viewport must not create an alpha notch at -1",
                justBeyond > 0.99f);
        assertEquals(1f, InventoryDeckGeometry.bottomEdgeAlpha(
                anchor, shortViewport, OUTER_HEIGHT, 0f), EPSILON);
    }

    @Test
    public void attachedRangeIsCenteredAndClampsFirstLastAndEmptyCounts() {
        InventoryDeckGeometry.RowRange start =
                InventoryDeckGeometry.attachedRowRange(0f, 10, 9);
        assertEquals(new InventoryDeckGeometry.RowRange(0, 8), start);

        InventoryDeckGeometry.RowRange middle =
                InventoryDeckGeometry.attachedRowRange(4.2f, 20, 9);
        assertEquals(new InventoryDeckGeometry.RowRange(0, 8), middle);
        InventoryDeckGeometry.RowRange handoff =
                InventoryDeckGeometry.attachedRowRange(4.5f, 20, 9);
        assertEquals(new InventoryDeckGeometry.RowRange(1, 9), handoff);

        InventoryDeckGeometry.RowRange tail =
                InventoryDeckGeometry.attachedRowRange(4f, 5, 9);
        assertEquals(new InventoryDeckGeometry.RowRange(0, 4), tail);
        InventoryDeckGeometry.RowRange oddTail =
                InventoryDeckGeometry.attachedRowRange(3f, 7, 9);
        assertEquals(new InventoryDeckGeometry.RowRange(0, 6), oddTail);

        InventoryDeckGeometry.RowRange end =
                InventoryDeckGeometry.attachedRowRange(9f, 10, 9);
        assertEquals(new InventoryDeckGeometry.RowRange(1, 9), end);
        assertEquals(new InventoryDeckGeometry.RowRange(0, 0),
                InventoryDeckGeometry.attachedRowRange(0f, 1, 9));
        assertEquals(0, InventoryDeckGeometry.attachedRowRange(0f, 0, 9).size());
    }

    private static float topAtDistance(float anchor, float distance) {
        return InventoryDeckGeometry.rowTop(anchor, 0, -distance,
                VISUAL_PITCH, OUTER_HEIGHT);
    }
}
