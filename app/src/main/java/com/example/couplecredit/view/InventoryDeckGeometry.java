package com.example.couplecredit.view;

/**
 * Pure geometry and visual rules for the inventory deck.
 *
 * <p>The layout manager keeps a continuous row offset while a gesture or a
 * fling is in progress. This class deliberately has no Android dependency so
 * the interpolation rules can be checked with ordinary JVM tests.</p>
 */
public final class InventoryDeckGeometry {

    /** The default distance between the centers of the three readable rows, in dp. */
    public static final float DEFAULT_ROW_PITCH_DP = 96f;

    /** The physical scroll distance for one row phase, in dp. */
    public static final float DEFAULT_SCROLL_STEP_DP = 104f;

    /** Nine attached rows expose roughly four rows on either side of focus. */
    public static final int DEFAULT_MAX_ATTACHED_ROWS = 9;

    /** The ordinary card root height before it becomes a compact strip. */
    public static final float FULL_ROOT_HEIGHT_DP = 88f;

    /** The compact card exposes a whole 32dp strip without squashing its text. */
    public static final float COMPACT_ROOT_HEIGHT_DP = 32f;

    /** The expanded outer height includes the 4dp top and bottom item margins. */
    public static final float EXPANDED_OUTER_HEIGHT_DP = 96f;

    /** The compact outer height includes the same 4dp item margins. */
    public static final float COMPACT_OUTER_HEIGHT_DP = 40f;

    /** Deep rows keep a small amount of base opacity; edge fades own recycling. */
    public static final float MIN_DEPTH_ALPHA = 0.94f;

    /** The only intentional near-card tilt is a subtle twelve-degree outward bend. */
    public static final float MAX_ROTATION_X_DEGREES = 12f;

    /** The focal card's center sits in the upper-middle of the viewport. */
    public static final float FOCAL_CENTER_FRACTION = 0.36f;

    /** Detail/compact layers crossfade before the root starts changing height. */
    public static final float CONTENT_FADE_START_DISTANCE = 1f;
    public static final float CONTENT_FADE_END_DISTANCE = 1.2f;

    /** Physical root height changes only after content has become compact. */
    public static final float SHRINK_START_DISTANCE = 1.2f;
    public static final float SHRINK_END_DISTANCE = 2f;

    /** The upper exiting card settles before it leaves the viewport. */
    public static final float ROTATION_END_DISTANCE = 2f;

    /** The lower concave wheel shares its entrance tangent with the convex wheel. */
    private static final float LOWER_WHEEL_EXIT_DISTANCE = 3.4f;

    /** Spacing starts closing before the root shrinks, then settles after it. */
    public static final float PITCH_TRANSITION_START_DISTANCE = 1.1f;
    public static final float PITCH_TRANSITION_END_DISTANCE = 2.3f;

    private static final float Z_STEP = 0.5f;
    private static final float EDGE_FADE_ROWS = 1f;

    private InventoryDeckGeometry() {
        // Utility class.
    }

    /** Returns a finite non-negative row offset clamped to the available rows. */
    public static float clampRowOffset(float rowOffset, int rowCount) {
        if (rowCount <= 1 || Float.isNaN(rowOffset) || Float.isInfinite(rowOffset)) {
            return 0f;
        }
        float max = rowCount - 1f;
        return clamp(rowOffset, 0f, max);
    }

    /** Converts a pixel scroll offset to a row offset, guarding bad metrics. */
    public static float rowOffsetFromPixels(float scrollOffsetPx, float rowPitchPx,
                                             int rowCount) {
        if (rowPitchPx <= 0f || Float.isNaN(rowPitchPx)
                || Float.isInfinite(rowPitchPx) || Float.isNaN(scrollOffsetPx)
                || Float.isInfinite(scrollOffsetPx)) {
            return 0f;
        }
        return clampRowOffset(scrollOffsetPx / rowPitchPx, rowCount);
    }

    /** Chooses the nearest row to focus when the deck becomes idle. */
    public static int focusedRow(float rowOffset, int rowCount) {
        if (rowCount <= 0) {
            return RecyclerRow.NONE;
        }
        float clamped = clampRowOffset(rowOffset, rowCount);
        return clamp(Math.round(clamped), 0, rowCount - 1);
    }

    /** Returns the signed distance, in rows, from the focused position. */
    public static float signedDistance(int row, float rowOffset) {
        return row - rowOffset;
    }

    /** Computes the top of a row from an expanded outer anchor. */
    public static float rowTop(float anchorTop, int row, float rowOffset,
                               float rowPitchPx) {
        return rowTop(anchorTop, row, rowOffset, rowPitchPx, rowPitchPx);
    }

    /**
     * Computes a row top from its projected center and its current outer height.
     *
     * <p>The final argument is the constant expanded outer height, rather than
     * a per-frame maximum. This keeps the focal anchor stable while rows below
     * it transition to compact strips.</p>
     */
    public static float rowTop(float anchorTop, int row, float rowOffset,
                               float rowPitchPx, float expandedOuterHeightPx) {
        float distance = safeDistance(row, rowOffset);
        float actualOuterHeight = actualOuterHeight(distance, expandedOuterHeightPx);
        return rowTop(anchorTop, row, rowOffset, rowPitchPx,
                expandedOuterHeightPx, actualOuterHeight);
    }

    /** Same as the five-argument overload with an explicit current outer height. */
    public static float rowTop(float anchorTop, int row, float rowOffset,
                               float rowPitchPx, float expandedOuterHeightPx,
                               float actualOuterHeightPx) {
        float safeExpanded = positiveOrZero(expandedOuterHeightPx);
        float center = projectedCenter(anchorTop, row, rowOffset,
                rowPitchPx, safeExpanded);
        float actual = isPositiveFinite(actualOuterHeightPx)
                ? actualOuterHeightPx
                : actualOuterHeight(signedDistance(row, rowOffset), safeExpanded);
        return center - actual * 0.5f;
    }

    /** Returns the projected center for a row relative to the focal anchor. */
    public static float projectedCenter(float anchorTop, int row, float rowOffset,
                                        float rowPitchPx, float expandedOuterHeightPx) {
        float safeAnchor = finiteOrZero(anchorTop);
        float safeExpanded = positiveOrZero(expandedOuterHeightPx);
        float distance = safeDistance(row, rowOffset);
        float nearPitch = nearbyPitch(rowPitchPx, safeExpanded);
        return safeAnchor + safeExpanded * 0.5f
                + projectDistance(distance, nearPitch, safeExpanded);
    }

    /** Returns the integrated center distance for a signed row distance. */
    public static float projectedDistance(float signedDistance, float rowPitchPx,
                                          float expandedOuterHeightPx) {
        float distance = finiteOrZero(signedDistance);
        float nearPitch = nearbyPitch(rowPitchPx, positiveOrZero(expandedOuterHeightPx));
        return projectDistance(distance, nearPitch, positiveOrZero(expandedOuterHeightPx));
    }

    /** Returns the local center pitch at a signed distance. */
    public static float localPitch(float signedDistance, float rowPitchPx,
                                   float expandedOuterHeightPx) {
        float distance = finiteOrZero(signedDistance);
        float nearPitch = nearbyPitch(rowPitchPx, positiveOrZero(expandedOuterHeightPx));
        // The upper exit retains full row spacing so its edge fade is predictable.
        if (distance < 0f) {
            return nearPitch;
        }
        float compactPitch = compactOuterPitch(positiveOrZero(expandedOuterHeightPx));
        if (distance <= PITCH_TRANSITION_START_DISTANCE) {
            return nearPitch;
        }
        if (distance >= PITCH_TRANSITION_END_DISTANCE) {
            return compactPitch;
        }
        float t = smoothStep((distance - PITCH_TRANSITION_START_DISTANCE)
                / (PITCH_TRANSITION_END_DISTANCE - PITCH_TRANSITION_START_DISTANCE));
        return lerp(nearPitch, compactPitch, t);
    }

    /** Returns the focal top for the fixed upper-middle center anchor. */
    public static float focalAnchorTop(float viewportTop, float viewportHeight,
                                       float expandedOuterHeightPx) {
        float safeTop = finiteOrZero(viewportTop);
        float safeViewportHeight = isPositiveFinite(viewportHeight) ? viewportHeight : 0f;
        float safeCardHeight = isPositiveFinite(expandedOuterHeightPx)
                ? expandedOuterHeightPx : 0f;
        return safeTop + safeViewportHeight * FOCAL_CENTER_FRACTION
                - safeCardHeight * 0.5f;
    }

    /**
     * Returns a 0..1 geometry expansion: one while the full card is readable,
     * zero once the compact 32dp strip has settled.
     */
    public static float expansion(float signedDistance) {
        float distance = finiteOrZero(signedDistance);
        // Only lower rows become compact. Upper rows leave through the whole
        // card edge fade so their actions never turn into a second summary.
        if (distance <= SHRINK_START_DISTANCE) {
            return 1f;
        }
        if (distance >= SHRINK_END_DISTANCE) {
            return 0f;
        }
        return 1f - smoothStep((distance - SHRINK_START_DISTANCE)
                / (SHRINK_END_DISTANCE - SHRINK_START_DISTANCE));
    }

    /** Alias that makes call sites describing geometry transitions explicit. */
    public static float geometryExpansion(float signedDistance) {
        return expansion(signedDistance);
    }

    /** Returns the current root height from an expanded root height in pixels. */
    public static float actualRootHeight(float signedDistance, float expandedRootHeightPx) {
        float expanded = positiveOrZero(expandedRootHeightPx);
        float compact = expanded * COMPACT_ROOT_HEIGHT_DP / FULL_ROOT_HEIGHT_DP;
        return lerp(compact, expanded, expansion(signedDistance));
    }

    /** Returns the current outer height, including the stable item margins. */
    public static float actualOuterHeight(float signedDistance, float expandedOuterHeightPx) {
        float expanded = positiveOrZero(expandedOuterHeightPx);
        float compact = expanded * COMPACT_OUTER_HEIGHT_DP / EXPANDED_OUTER_HEIGHT_DP;
        return lerp(compact, expanded, expansion(signedDistance));
    }

    /** Full details layer alpha. It reaches zero before root height changes. */
    public static float fullContentAlpha(float signedDistance) {
        float distance = finiteOrZero(signedDistance);
        if (distance <= CONTENT_FADE_START_DISTANCE) {
            return 1f;
        }
        if (distance >= CONTENT_FADE_END_DISTANCE) {
            return 0f;
        }
        return 1f - smoothStep((distance - CONTENT_FADE_START_DISTANCE)
                / (CONTENT_FADE_END_DISTANCE - CONTENT_FADE_START_DISTANCE));
    }

    /** Compact category/quantity layer alpha complementary to full content. */
    public static float compactContentAlpha(float signedDistance) {
        return 1f - fullContentAlpha(signedDistance);
    }

    /** Base card alpha; viewport edge methods own whole-card fading. */
    public static float baseAlpha(float signedDistance) {
        float distance = Math.abs(finiteOrZero(signedDistance));
        if (distance <= 0f) {
            return 1f;
        }
        if (distance >= SHRINK_END_DISTANCE) {
            return MIN_DEPTH_ALPHA;
        }
        // A restrained depth cue keeps the neighboring cards distinct without
        // making the whole card flicker during a fast scroll.
        float t = smoothStep(Math.min(distance, CONTENT_FADE_START_DISTANCE)
                / CONTENT_FADE_START_DISTANCE);
        return lerp(1f, MIN_DEPTH_ALPHA, t);
    }

    /** Fade the whole upper card only after it passes the first neighbor. */
    public static float topEdgeAlpha(float cardTop, float viewportTop, float cardHeight,
                                     float signedDistance) {
        float distance = finiteOrZero(signedDistance);
        if (!(distance < -CONTENT_FADE_START_DISTANCE)) {
            return 1f;
        }

        float edgeBlend = smoothStep(((-distance) - CONTENT_FADE_START_DISTANCE)
                / EDGE_FADE_ROWS);
        float distanceFade = 1f - edgeBlend;
        if (!isPositiveFinite(cardHeight) || !isFinite(cardTop)
                || !isFinite(viewportTop)) {
            return clamp(distanceFade, 0f, 1f);
        }

        // The position band makes the fade follow the actual viewport edge;
        // the distance band keeps the handoff smooth when card sizes differ.
        float fadeStart = viewportTop - cardHeight * 0.08f;
        float fadeEnd = viewportTop - cardHeight;
        float positionFade = smoothStep((cardTop - fadeEnd) / (fadeStart - fadeEnd));
        float blendedPositionFade = 1f - edgeBlend * (1f - positionFade);
        return clamp(distanceFade * blendedPositionFade, 0f, 1f);
    }

    /** Fade a whole lower card only while its actual outer bounds leave the viewport. */
    public static float bottomEdgeAlpha(float cardTop, float viewportBottom,
                                        float cardHeight) {
        return bottomEdgeAlpha(cardTop, viewportBottom, cardHeight, 1f);
    }

    /**
     * Lower edge fade that starts before a card is cut by the viewport. The
     * focused and upper cards never use this fade, which avoids a short
     * viewport dimming the focused card at its own anchor.
     */
    public static float bottomEdgeAlpha(float cardTop, float viewportBottom,
                                        float cardHeight, float signedDistance) {
        if (!(signedDistance > 0f)) {
            return 1f;
        }
        if (!isPositiveFinite(cardHeight) || !isFinite(cardTop)
                || !isFinite(viewportBottom)) {
            return 1f;
        }
        float cardBottom = cardTop + cardHeight;
        float fadeLength = Math.max(16f, cardHeight * 0.5f);
        float fadeStart = viewportBottom - fadeLength;
        if (cardBottom <= fadeStart) {
            return 1f;
        }
        float progress = smoothStep((cardBottom - fadeStart) / fadeLength);
        return clamp(1f - progress, 0f, 1f);
    }

    /**
     * Computes the visual state for one row. The result is a value object so
     * tests can inspect all rules without depending on Android View classes.
     */
    public static VisualState visualState(float signedDistance, int maxDepth) {
        signedDistance = finiteOrZero(signedDistance);
        float distance = Math.abs(signedDistance);
        float rotationMagnitude;
        if (distance <= CONTENT_FADE_START_DISTANCE) {
            rotationMagnitude = MAX_ROTATION_X_DEGREES * smoothStep(distance);
        } else if (distance >= ROTATION_END_DISTANCE) {
            rotationMagnitude = 0f;
        } else {
            rotationMagnitude = MAX_ROTATION_X_DEGREES
                    * (1f - smoothStep((distance - CONTENT_FADE_START_DISTANCE)
                    / (ROTATION_END_DISTANCE - CONTENT_FADE_START_DISTANCE)));
        }

        // Android's positive rotationX lifts the upper edge toward the viewer.
        // Reversing the signed distance therefore makes upper rows positive and
        // lower rows negative, producing an outward (convex) wheel.
        float rotation = -Math.copySign(rotationMagnitude, finiteOrZero(signedDistance));
        if (distance == 0f) {
            rotation = 0f;
        }
        if (signedDistance > 1f) {
            // One continuous S-shaped surface: the negative exit tangent of
            // the convex wheel becomes the entrance to the concave wheel.
            // Cross zero at 2.2 rows, then turn the lower strips toward us.
            // Smoothstep gives matching zero slopes at both ends; no row or
            // focus-index branch is involved when dragging in either direction.
            rotation = MAX_ROTATION_X_DEGREES * (2f * smoothStep(
                    (signedDistance - 1f) / (LOWER_WHEEL_EXIT_DISTANCE - 1f)) - 1f);
        }
        // Keep the near-facing perspective edge within the horizontal margins.
        // A slight narrowing at the lower wheel's center suggests recession.
        // The text keeps its natural height throughout both wheels.
        float nearWidthBlend = distance <= 1f ? smoothStep(distance)
                : 1f - smoothStep(distance - 1f);
        float lowerRecession = smoothStep(signedDistance - 1f)
                * (1f - smoothStep((signedDistance - 2.2f) / 1.2f));
        return new VisualState(
                1f - 0.03f * nearWidthBlend - 0.015f * lowerRecession,
                1f,
                depthAlpha(signedDistance, maxDepth),
                rotation,
                zForDistance(signedDistance, maxDepth),
                false,
                rootHeightFraction(signedDistance),
                outerHeightFraction(signedDistance),
                fullContentAlpha(signedDistance),
                compactContentAlpha(signedDistance));
    }

    /** Returns a symmetric z ordering with the focused row highest. */
    public static float zForDistance(float signedDistance, int maxDepth) {
        float distance = Math.abs(finiteOrZero(signedDistance));
        return -Math.min(distance, Math.max(1, maxDepth)) * Z_STEP;
    }

    /** Returns the root height as a fraction of its expanded height. */
    public static float rootHeightFraction(float signedDistance) {
        return actualRootHeight(signedDistance, FULL_ROOT_HEIGHT_DP) / FULL_ROOT_HEIGHT_DP;
    }

    /** Returns the outer height as a fraction of its expanded height. */
    public static float outerHeightFraction(float signedDistance) {
        return actualOuterHeight(signedDistance, EXPANDED_OUTER_HEIGHT_DP)
                / EXPANDED_OUTER_HEIGHT_DP;
    }

    private static float depthAlpha(float signedDistance, int maxDepth) {
        float distance = Math.abs(finiteOrZero(signedDistance));
        int safeDepth = Math.max(1, maxDepth);
        float fadeStart = Math.max(1f, safeDepth - 1f);
        float fadeRange = Math.max(0.0001f, safeDepth - fadeStart);
        float outerFade = smoothStep((distance - fadeStart) / fadeRange);
        return clamp(baseAlpha(signedDistance) * (1f - outerFade), 0f, 1f);
    }

    /** Returns the first and last row that should be attached. */
    public static RowRange attachedRowRange(float rowOffset, int rowCount,
                                             int maxAttachedRows) {
        if (rowCount <= 0 || maxAttachedRows <= 0) {
            return RowRange.EMPTY;
        }
        int count = Math.min(rowCount, maxAttachedRows);
        float clampedOffset = clampRowOffset(rowOffset, rowCount);
        int focus = clamp(Math.round(clampedOffset), 0, rowCount - 1);
        int before = (count - 1) / 2;
        int first = focus - before;
        int last = first + count - 1;

        if (first < 0) {
            last += -first;
            first = 0;
        }
        if (last >= rowCount) {
            int shift = last - rowCount + 1;
            first -= shift;
            last -= shift;
        }

        first = Math.max(0, first);
        last = Math.min(rowCount - 1, last);
        if (last < first) {
            return RowRange.EMPTY;
        }
        return new RowRange(first, last);
    }

    private static float projectDistance(float distance, float nearPitch,
                                         float expandedOuterHeight) {
        float safeDistance = finiteOrZero(distance);
        if (safeDistance < 0f) {
            // Keep upper rows at the established full pitch so their exit edge
            // remains stable while the lower stack folds into compact strips.
            return safeDistance * nearPitch;
        }
        float near = isPositiveFinite(nearPitch) ? nearPitch : positiveOrZero(expandedOuterHeight);
        float compact = compactOuterPitch(positiveOrZero(expandedOuterHeight));
        if (!(near > 0f)) {
            return 0f;
        }
        float transitionLength = PITCH_TRANSITION_END_DISTANCE
                - PITCH_TRANSITION_START_DISTANCE;
        float beyond = safeDistance - PITCH_TRANSITION_START_DISTANCE;
        if (safeDistance <= PITCH_TRANSITION_START_DISTANCE) {
            return safeDistance * near;
        }
        float transitionDistance = Math.min(beyond, transitionLength);
        float t = transitionDistance / transitionLength;

        // Integral of smoothStep(t)=3t^2-2t^3 is t^3 - 0.5t^4.
        float smoothIntegral = t * t * t - 0.5f * t * t * t * t;
        float projected = near * PITCH_TRANSITION_START_DISTANCE
                + near * transitionDistance
                + (compact - near) * transitionLength * smoothIntegral;
        if (beyond > transitionLength) {
            projected += compact * (beyond - transitionLength);
        }
        return projected;
    }

    private static float nearbyPitch(float requestedPitch, float expandedOuterHeight) {
        float requested = isPositiveFinite(requestedPitch)
                ? requestedPitch : expandedOuterHeight;
        float safeExpanded = positiveOrZero(expandedOuterHeight);
        if (!(safeExpanded > 0f)) {
            return Math.max(0f, requested);
        }
        // Center rows have at least their expanded outer height between centers;
        // default margins therefore meet without wiping across one another.
        return Math.max(safeExpanded, requested);
    }

    private static float compactOuterPitch(float expandedOuterHeight) {
        if (!isPositiveFinite(expandedOuterHeight)) {
            return 0f;
        }
        return expandedOuterHeight * COMPACT_OUTER_HEIGHT_DP / EXPANDED_OUTER_HEIGHT_DP;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float smoothStep(float value) {
        float t = clamp(value, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * clamp(amount, 0f, 1f);
    }

    private static float safeDistance(int row, float rowOffset) {
        return finiteOrZero(row - finiteOrZero(rowOffset));
    }

    private static float positiveOrZero(float value) {
        return isPositiveFinite(value) ? value : 0f;
    }

    private static float finiteOrZero(float value) {
        return isFinite(value) ? value : 0f;
    }

    private static boolean isPositiveFinite(float value) {
        return value > 0f && isFinite(value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Small sentinel holder to avoid coupling the geometry to a manager. */
    private static final class RecyclerRow {
        private static final int NONE = -1;

        private RecyclerRow() {
        }
    }

    /** Immutable row range used by the layout manager and JVM tests. */
    public static final class RowRange {
        public static final RowRange EMPTY = new RowRange(-1, -1);

        public final int first;
        public final int last;

        public RowRange(int first, int last) {
            this.first = first;
            this.last = last;
        }

        public int size() {
            return first < 0 || last < first ? 0 : last - first + 1;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RowRange)) {
                return false;
            }
            RowRange that = (RowRange) other;
            return first == that.first && last == that.last;
        }

        @Override
        public int hashCode() {
            return 31 * first + last;
        }

        @Override
        public String toString() {
            return "RowRange{" + first + ".." + last + "}";
        }
    }

    /** Immutable visual values applied to one row of cards. */
    public static final class VisualState {
        public final float scale;
        public final float scaleY;
        public final float alpha;
        public final float rotationX;
        public final float translationZ;
        public final boolean shouldBlur;
        public final float rootHeightFraction;
        public final float outerHeightFraction;
        public final float fullContentAlpha;
        public final float compactContentAlpha;

        public VisualState(float scale, float alpha, float rotationX,
                           float translationZ, boolean shouldBlur) {
            this(scale, scale, alpha, rotationX, translationZ, shouldBlur,
                    1f, 1f, 1f, 0f);
        }

        public VisualState(float scale, float scaleY, float alpha, float rotationX,
                           float translationZ, boolean shouldBlur) {
            this(scale, scaleY, alpha, rotationX, translationZ, shouldBlur,
                    1f, 1f, 1f, 0f);
        }

        public VisualState(float scale, float scaleY, float alpha, float rotationX,
                           float translationZ, boolean shouldBlur,
                           float rootHeightFraction, float outerHeightFraction,
                           float fullContentAlpha, float compactContentAlpha) {
            this.scale = scale;
            this.scaleY = scaleY;
            this.alpha = alpha;
            this.rotationX = rotationX;
            this.translationZ = translationZ;
            this.shouldBlur = shouldBlur;
            this.rootHeightFraction = rootHeightFraction;
            this.outerHeightFraction = outerHeightFraction;
            this.fullContentAlpha = fullContentAlpha;
            this.compactContentAlpha = compactContentAlpha;
        }
    }
}
