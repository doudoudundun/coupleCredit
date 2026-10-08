package com.example.couplecredit.view;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;

/**
 * A centered inventory roller that keeps the focused row readable and stacks
 * nearby rows on both sides like a small card deck.
 *
 * <p>The manager owns only positioning and visual interpolation.  The adapter
 * remains an ordinary {@link RecyclerView.Adapter}; no notify or rebinding is
 * performed while a gesture is moving.  At most nine logical rows are kept
 * attached, and rows are added or recycled only when the moving window crosses
 * a half-row handoff boundary.</p>
 *
 * <p>Typical setup:</p>
 * <pre>
 * InventoryDeckLayoutManager manager =
 *         new InventoryDeckLayoutManager(context, 1);
 * recyclerView.setLayoutManager(manager);
 * manager.attachToRecyclerView(recyclerView);
 * </pre>
 *
 * <p>For touch exploration, use a regular {@code LinearLayoutManager} while
 * accessibility is enabled.  The deck can then be restored when touch
 * exploration is disabled.  This keeps the hidden cards and their action
 * buttons out of TalkBack's virtual focus order.</p>
 */
public class InventoryDeckLayoutManager extends RecyclerView.LayoutManager
        implements RecyclerView.SmoothScroller.ScrollVectorProvider {

    private static final int NO_POSITION = RecyclerView.NO_POSITION;
    private static final float MAX_ROW_OFFSET_EPSILON = 0.0001f;

    private final float density;
    private int spanCount;
    private int itemCount;
    private float rowPitchDp = InventoryDeckGeometry.DEFAULT_ROW_PITCH_DP;
    private float rowPitchPx;
    private float scrollStepDp = InventoryDeckGeometry.DEFAULT_SCROLL_STEP_DP;
    private float scrollStepPx;
    private int maxAttachedRows = InventoryDeckGeometry.DEFAULT_MAX_ATTACHED_ROWS;
    private int maxDepth = visualDepthForAttachedRows(maxAttachedRows);
    private float scrollOffsetPx;
    private int pendingScrollPosition = NO_POSITION;
    private float pendingRestoredRowOffset = Float.NaN;
    private int cardHeightPx;
    private boolean snapOnIdle = true;
    private boolean depthBlurEnabled = true;
    private RecyclerView attachedRecyclerView;
    private RenderEffect[] depthBlurEffects;

    private final RecyclerView.OnScrollListener snapListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView,
                                                  int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        snapToNearestRow(recyclerView);
                    }
                }
            };

    public InventoryDeckLayoutManager(@NonNull Context context) {
        this(context, 1);
    }

    public InventoryDeckLayoutManager(@NonNull Context context, int spanCount) {
        density = Math.max(0.1f, context.getResources().getDisplayMetrics().density);
        this.spanCount = Math.max(1, spanCount);
        rowPitchPx = rowPitchDp * density;
        scrollStepPx = scrollStepDp * density;
        setItemPrefetchEnabled(true);
    }

    /**
     * Keeps RecyclerView's own measure pass in charge of the independent
     * viewport height supplied by the parent Fragment.
     */
    @Override
    public boolean isAutoMeasureEnabled() {
        return true;
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public int getSpanCount() {
        return spanCount;
    }

    public void setSpanCount(int spanCount) {
        int normalized = Math.max(1, spanCount);
        if (this.spanCount == normalized) {
            return;
        }
        float currentRowOffset = getScrollRowOffset();
        this.spanCount = normalized;
        scrollOffsetPx = currentRowOffset * scrollStepPx;
        clampScrollOffset();
        requestLayout();
    }

    public float getRowPitchDp() {
        return rowPitchDp;
    }

    /**
     * Changes the compact distance between nearby row tops.  The default is
     * the 96dp outer size of the compact inventory card.  The geometry clamps
     * this value to a near-full card spacing when a measured outer height is
     * available, keeping the center pair from wiping across one another.
     */
    public void setRowPitchDp(float rowPitchDp) {
        if (Float.isNaN(rowPitchDp) || Float.isInfinite(rowPitchDp)) {
            return;
        }
        float normalized = Math.max(1f, rowPitchDp);
        if (Math.abs(this.rowPitchDp - normalized) < MAX_ROW_OFFSET_EPSILON) {
            return;
        }
        float currentRowOffset = getScrollRowOffset();
        this.rowPitchDp = normalized;
        rowPitchPx = normalized * density;
        scrollOffsetPx = currentRowOffset * scrollStepPx;
        clampScrollOffset();
        requestLayout();
    }

    public float getScrollStepDp() {
        return scrollStepDp;
    }

    /**
     * Sets the physical distance for one row phase.  The default 104dp keeps
     * the roller following a compact-card drag while its visual near pitch
     * remains tied to the measured card height.
     */
    public void setScrollStepDp(float scrollStepDp) {
        if (Float.isNaN(scrollStepDp) || Float.isInfinite(scrollStepDp)) {
            return;
        }
        float normalized = Math.max(1f, scrollStepDp);
        if (Math.abs(this.scrollStepDp - normalized) < MAX_ROW_OFFSET_EPSILON) {
            return;
        }
        float currentRowOffset = getScrollRowOffset();
        this.scrollStepDp = normalized;
        this.scrollStepPx = normalized * density;
        this.scrollOffsetPx = currentRowOffset * scrollStepPx;
        clampScrollOffset();
        requestLayout();
    }

    public int getMaxAttachedRows() {
        return maxAttachedRows;
    }

    /**
     * Limits the attached window.  Nine rows is the intended default; a
     * larger value is allowed for unusual cards but is still capped at 16 to
     * avoid accidentally turning the manager into a full-list layout.
     */
    public void setMaxAttachedRows(int maxAttachedRows) {
        int normalized = Math.max(2, Math.min(16, maxAttachedRows));
        if (this.maxAttachedRows == normalized) {
            return;
        }
        this.maxAttachedRows = normalized;
        this.maxDepth = visualDepthForAttachedRows(normalized);
        depthBlurEffects = null;
        requestLayout();
    }

    public boolean isSnapOnIdle() {
        return snapOnIdle;
    }

    public void setSnapOnIdle(boolean snapOnIdle) {
        this.snapOnIdle = snapOnIdle;
    }

    public boolean isDepthBlurEnabled() {
        return depthBlurEnabled;
    }

    public void setDepthBlurEnabled(boolean enabled) {
        if (depthBlurEnabled == enabled) {
            return;
        }
        depthBlurEnabled = enabled;
        if (!enabled) {
            depthBlurEffects = null;
        }
        applyVisualStateToAttachedChildren();
    }

    /**
     * Registers the idle snap listener.  Calling this after
     * {@code setLayoutManager} is explicit and safe; lifecycle callbacks also
     * call it automatically when the manager is attached.
     */
    public void attachToRecyclerView(@Nullable RecyclerView recyclerView) {
        if (attachedRecyclerView == recyclerView) {
            return;
        }
        if (attachedRecyclerView != null) {
            attachedRecyclerView.removeOnScrollListener(snapListener);
        }
        attachedRecyclerView = recyclerView;
        if (attachedRecyclerView != null) {
            attachedRecyclerView.addOnScrollListener(snapListener);
        }
    }

    @Override
    public void onAttachedToWindow(@NonNull RecyclerView view) {
        super.onAttachedToWindow(view);
        attachToRecyclerView(view);
    }

    @Override
    public void onDetachedFromWindow(@NonNull RecyclerView view,
                                     @NonNull RecyclerView.Recycler recycler) {
        resetAttachedVisualState();
        attachToRecyclerView(null);
        super.onDetachedFromWindow(view, recycler);
    }

    /**
     * Continuous row phase.  0 means the first row is at the front; an
     * integer n means row n is at the front.  The value is useful to touch
     * mediation and is intentionally not rounded during a drag.
     */
    public float getScrollRowOffset() {
        return InventoryDeckGeometry.rowOffsetFromPixels(scrollOffsetPx, scrollStepPx,
                getRowCount(itemCount));
    }

    /** Returns the nearest clamped row that will be front after idle snap. */
    public int getFocusedRow() {
        return InventoryDeckGeometry.focusedRow(getScrollRowOffset(),
                getRowCount(itemCount));
    }

    /** Returns true for either item in the current focused row. */
    public boolean isItemFocused(int position) {
        if (position < 0 || position >= itemCount || spanCount <= 0) {
            return false;
        }
        return position / spanCount == getFocusedRow();
    }

    /**
     * Stops at the first/last row and makes the requested row the front row on
     * the next layout pass.
     */
    @Override
    public void scrollToPosition(int position) {
        if (position < 0) {
            pendingScrollPosition = 0;
        } else {
            pendingScrollPosition = position;
        }
        if (attachedRecyclerView != null) {
            attachedRecyclerView.stopScroll();
        }
        requestLayout();
    }

    /** Convenience overload for callers that hold the manager directly. */
    public void smoothScrollToPosition(int position) {
        if (attachedRecyclerView != null) {
            attachedRecyclerView.smoothScrollToPosition(position);
        } else {
            pendingScrollPosition = Math.max(0, position);
            requestLayout();
        }
    }

    @Override
    public void smoothScrollToPosition(@NonNull RecyclerView recyclerView,
                                       @NonNull RecyclerView.State state,
                                       int position) {
        if (state.getItemCount() <= 0) {
            return;
        }
        int target = Math.max(0, Math.min(state.getItemCount() - 1, position));
        if (scrollStepPx <= 0f) {
            pendingScrollPosition = target;
            requestLayout();
            return;
        }

        LinearSmoothScroller smoothScroller = new DeckSmoothScroller(recyclerView, target);
        smoothScroller.setTargetPosition(target);
        startSmoothScroll(smoothScroller);
    }

    @Override
    public PointF computeScrollVectorForPosition(int targetPosition) {
        int targetRow = Math.max(0, targetPosition) / Math.max(1, spanCount);
        float targetOffset = targetRow * scrollStepPx;
        float direction = targetOffset >= scrollOffsetPx ? 1f : -1f;
        return new PointF(0f, direction);
    }

    @Override
    public boolean canScrollVertically() {
        return getRowCount(itemCount) > 1;
    }

    @Override
    public boolean canScrollHorizontally() {
        return false;
    }

    @Override
    public int scrollVerticallyBy(int dy, @NonNull RecyclerView.Recycler recycler,
                                  @NonNull RecyclerView.State state) {
        if (dy == 0 || state.getItemCount() <= 0 || scrollStepPx <= 0f) {
            return 0;
        }
        itemCount = state.getItemCount();
        float oldOffset = scrollOffsetPx;
        float maxOffset = getMaxScrollOffsetPx(itemCount);
        float newOffset = Math.max(0f, Math.min(maxOffset, oldOffset + dy));
        float consumedFloat = newOffset - oldOffset;
        if (Math.abs(consumedFloat) < MAX_ROW_OFFSET_EPSILON) {
            return 0;
        }
        scrollOffsetPx = newOffset;
        ensureAttachedRows(recycler, state);
        applyLayoutToAttachedChildren(false);
        return Math.round(consumedFloat);
    }

    @Override
    public void onLayoutChildren(@NonNull RecyclerView.Recycler recycler,
                                 @NonNull RecyclerView.State state) {
        itemCount = Math.max(0, state.getItemCount());
        if (itemCount == 0) {
            scrollOffsetPx = 0f;
            pendingScrollPosition = NO_POSITION;
            removeAndRecycleAllViews(recycler);
            return;
        }

        if (!Float.isNaN(pendingRestoredRowOffset)) {
            scrollOffsetPx = pendingRestoredRowOffset * scrollStepPx;
            pendingRestoredRowOffset = Float.NaN;
        }
        if (pendingScrollPosition != NO_POSITION) {
            int targetPosition = Math.max(0, Math.min(itemCount - 1, pendingScrollPosition));
            scrollOffsetPx = (targetPosition / Math.max(1, spanCount)) * scrollStepPx;
            pendingScrollPosition = NO_POSITION;
        }
        clampScrollOffset();

        // A data-set/layout change needs a clean row window.  During ordinary
        // scrolling ensureAttachedRows below keeps existing children in place.
        detachAndScrapAttachedViews(recycler);
        ensureAttachedRows(recycler, state);
        applyLayoutToAttachedChildren(true);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        SavedState savedState = new SavedState();
        savedState.rowOffset = getScrollRowOffset();
        return savedState;
    }

    @Override
    public void onRestoreInstanceState(@Nullable Parcelable state) {
        if (!(state instanceof SavedState)) {
            return;
        }
        SavedState savedState = (SavedState) state;
        pendingRestoredRowOffset = savedState.rowOffset;
        requestLayout();
    }

    @Override
    public int computeVerticalScrollExtent(@NonNull RecyclerView.State state) {
        return Math.max(0, getHeight());
    }

    @Override
    public int computeVerticalScrollOffset(@NonNull RecyclerView.State state) {
        return Math.max(0, Math.round(scrollOffsetPx));
    }

    @Override
    public int computeVerticalScrollRange(@NonNull RecyclerView.State state) {
        return Math.max(getHeight(), Math.round(getHeight() + getMaxScrollOffsetPx(state.getItemCount())));
    }

    private void ensureAttachedRows(@NonNull RecyclerView.Recycler recycler,
                                    @NonNull RecyclerView.State state) {
        int rows = getRowCount(state.getItemCount());
        if (rows <= 0) {
            return;
        }
        InventoryDeckGeometry.RowRange range = InventoryDeckGeometry.attachedRowRange(
                getScrollRowOffset(), rows, maxAttachedRows);

        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            int position = getPosition(child);
            int row = position == NO_POSITION ? -1 : position / Math.max(1, spanCount);
            if (row < range.first || row > range.last) {
                removeAndRecycleView(child, recycler);
            }
        }

        // Add deeper rows first.  Translation Z remains the source of truth for
        // drawing/hit ordering, while this order is also a sensible fallback
        // for software rendering.
        for (int row = range.last; row >= range.first; row--) {
            for (int column = 0; column < spanCount; column++) {
                int position = row * spanCount + column;
                if (position >= state.getItemCount()) {
                    continue;
                }
                if (findViewByPosition(position) == null) {
                    View child = recycler.getViewForPosition(position);
                    addView(child);
                    measureChildForSlot(child, getColumnWidth(column));
                }
            }
        }
    }

    private void applyLayoutToAttachedChildren(boolean remeasure) {
        if (getChildCount() == 0) return;
        // Always anchor against the expanded card, never against a shrinking child.
        int expandedRootHeight = Math.round(InventoryDeckGeometry.FULL_ROOT_HEIGHT_DP * density);
        cardHeightPx = expandedRootHeight;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) child.getLayoutParams();
            int decorations = getDecoratedMeasuredHeight(child) - child.getMeasuredHeight();
            cardHeightPx = Math.max(cardHeightPx,
                    expandedRootHeight + lp.topMargin + lp.bottomMargin + decorations);
        }
        float offset = getScrollRowOffset();
        int viewportHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float anchorTop = InventoryDeckGeometry.focalAnchorTop(
                getPaddingTop(), viewportHeight, cardHeightPx);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int position = getPosition(child);
            if (position == NO_POSITION || position < 0 || position >= itemCount) continue;
            int row = position / Math.max(1, spanCount);
            int column = position % Math.max(1, spanCount);
            float distance = InventoryDeckGeometry.signedDistance(row, offset);
            InventoryDeckGeometry.VisualState visual = InventoryDeckGeometry.visualState(distance, maxDepth);
            int rootHeight = Math.round(InventoryDeckGeometry.actualRootHeight(distance, expandedRootHeight));
            // Only changing transition cards need a new measure. LP.height stays
            // at 88dp so ordinary lists can reuse the holder without a compact size.
            measureChildForHeight(child, getColumnWidth(column), rootHeight, remeasure);
            int outerHeight = getOuterMeasuredHeight(child);
            float top = InventoryDeckGeometry.rowTop(anchorTop, row, offset,
                    rowPitchPx, cardHeightPx, outerHeight);
            int topPx = Math.round(top);
            int left = getColumnLeft(column);
            layoutDecoratedWithMargins(child, left, topPx,
                    left + getColumnWidth(column), topPx + outerHeight);
            child.setTranslationY(top - topPx);
            child.setPivotX(child.getMeasuredWidth() * 0.5f);
            child.setPivotY(child.getMeasuredHeight() * 0.5f);
            child.setCameraDistance(1600f * density);
            child.setScaleX(visual.scale);
            child.setScaleY(visual.scaleY);
            float edgeAlpha = distance < 0f
                    ? InventoryDeckGeometry.topEdgeAlpha(child.getY(), getPaddingTop(),
                            child.getMeasuredHeight(), distance)
                    : distance > 0f
                            ? InventoryDeckGeometry.bottomEdgeAlpha(child.getY(),
                                    getHeight() - getPaddingBottom(), child.getMeasuredHeight()) : 1f;
            child.setAlpha(visual.alpha * edgeAlpha);
            child.setRotationX(visual.rotationX);
            child.setTranslationZ(visual.translationZ * density);
            setContentPresentation(child, visual.fullContentAlpha, visual.compactContentAlpha);
            applyDepthBlur(child, visual.shouldBlur ? distance : 0f);
        }
    }

    private void applyVisualStateToAttachedChildren() {
        applyLayoutToAttachedChildren(false);
    }

    private void setContentPresentation(View child, float fullAlpha, float compactAlpha) {
        View full = child.findViewById(R.id.layout_inventory_card);
        View compact = child.findViewById(R.id.layout_inventory_compact);
        if (full != null) {
            full.setAlpha(fullAlpha);
            full.setImportantForAccessibility(fullAlpha > 0.5f
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        if (compact != null) {
            compact.setAlpha(compactAlpha);
            compact.setImportantForAccessibility(compactAlpha >= 0.5f
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    private void measureChildForHeight(View child, int slotWidth, int rootHeight, boolean force) {
        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) child.getLayoutParams();
        int width = Math.max(1, slotWidth - lp.leftMargin - lp.rightMargin);
        int height = Math.max(1, rootHeight);
        if (force || child.isLayoutRequested() || child.getMeasuredWidth() != width
                || child.getMeasuredHeight() != height) {
            child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        }
    }

    private void applyDepthBlur(@NonNull View child, float signedDistance) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        float distance = Math.abs(signedDistance);
        int blurStep = Math.min(32, Math.round(distance / maxDepth * 32f));
        if (!depthBlurEnabled || blurStep == 0) {
            child.setRenderEffect(null);
            return;
        }
        ensureDepthBlurEffects();
        child.setRenderEffect(depthBlurEffects[blurStep]);
    }

    private void ensureDepthBlurEffects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (depthBlurEffects == null || depthBlurEffects.length != 33) {
            depthBlurEffects = new RenderEffect[33];
        }
        for (int layer = 1; layer <= 32; layer++) {
            if (depthBlurEffects[layer] != null) {
                continue;
            }
            // Small cached increments avoid a visible blur jump at row boundaries.
            // The centered card remains sharp even with subpixel snap rounding.
            float radius = Math.min(1f, maxDepth * 0.15f) * layer / 32f * density;
            depthBlurEffects[layer] = RenderEffect.createBlurEffect(radius, radius,
                    Shader.TileMode.CLAMP);
        }
    }

    private void resetAttachedVisualState() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            setContentPresentation(child, 1f, 0f);
            int position = getPosition(child);
            int column = position == NO_POSITION ? 0 : position % Math.max(1, spanCount);
            measureChildForHeight(child, getColumnWidth(column),
                    Math.round(InventoryDeckGeometry.FULL_ROOT_HEIGHT_DP * density), false);
            child.setClipBounds(null);
            child.setTranslationY(0f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setAlpha(1f);
            child.setRotationX(0f);
            child.setTranslationZ(0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                child.setRenderEffect(null);
            }
        }
    }

    private void clampScrollOffset() {
        scrollOffsetPx = Math.max(0f, Math.min(getMaxScrollOffsetPx(itemCount), scrollOffsetPx));
    }

    private void snapToNearestRow(@NonNull RecyclerView recyclerView) {
        if (!snapOnIdle || itemCount <= 0 || scrollStepPx <= 0f
                || recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }
        int targetRow = InventoryDeckGeometry.focusedRow(getScrollRowOffset(),
                getRowCount(itemCount));
        if (targetRow < 0) {
            return;
        }
        float targetOffset = targetRow * scrollStepPx;
        int dy = Math.round(targetOffset - scrollOffsetPx);
        if (dy == 0) {
            return;
        }
        recyclerView.smoothScrollBy(0, dy);
    }

    private int getRowCount(int count) {
        return count <= 0 ? 0 : (count + Math.max(1, spanCount) - 1) / Math.max(1, spanCount);
    }

    private static int visualDepthForAttachedRows(int attachedRows) {
        return Math.max(1, (Math.max(1, attachedRows) - 1) / 2);
    }

    private float getMaxScrollOffsetPx(int count) {
        return Math.max(0, getRowCount(count) - 1) * scrollStepPx;
    }

    private int getColumnWidth(int column) {
        int availableWidth = Math.max(0,
                getWidth() - getPaddingLeft() - getPaddingRight());
        int gaps = Math.max(0, spanCount - 1);
        int baseWidth = Math.max(1, (availableWidth - gaps) / Math.max(1, spanCount));
        int remainder = Math.max(0, availableWidth - gaps - baseWidth * spanCount);
        return baseWidth + (column < remainder ? 1 : 0);
    }

    private int getColumnLeft(int column) {
        int left = getPaddingLeft();
        for (int i = 0; i < column; i++) {
            left += getColumnWidth(i);
            if (i < spanCount - 1) {
                left += 1;
            }
        }
        return left;
    }

    private void measureChildForSlot(@NonNull View child, int slotWidth) {
        RecyclerView.LayoutParams layoutParams = (RecyclerView.LayoutParams) child.getLayoutParams();
        int horizontalMargins = layoutParams.leftMargin + layoutParams.rightMargin;
        int childWidth = Math.max(1, slotWidth - horizontalMargins);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY);
        int heightSpec;
        if (layoutParams.height >= 0) {
            heightSpec = View.MeasureSpec.makeMeasureSpec(layoutParams.height,
                    View.MeasureSpec.EXACTLY);
        } else {
            heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        }
        child.measure(widthSpec, heightSpec);
    }

    private int getOuterMeasuredHeight(@NonNull View child) {
        RecyclerView.LayoutParams layoutParams =
                (RecyclerView.LayoutParams) child.getLayoutParams();
        return getDecoratedMeasuredHeight(child)
                + layoutParams.topMargin + layoutParams.bottomMargin;
    }

    private final class DeckSmoothScroller extends LinearSmoothScroller {
        private final int targetPosition;

        DeckSmoothScroller(@NonNull RecyclerView recyclerView, int targetPosition) {
            super(recyclerView.getContext());
            this.targetPosition = targetPosition;
        }

        @Override
        protected float calculateSpeedPerPixel(@NonNull DisplayMetrics displayMetrics) {
            return 45f / displayMetrics.densityDpi;
        }

        @Override
        public int calculateDyToMakeVisible(@NonNull View view, int snapPreference) {
            int position = getPosition(view);
            int row = position == NO_POSITION ? targetPosition / Math.max(1, spanCount)
                    : position / Math.max(1, spanCount);
            return Math.round(row * scrollStepPx - scrollOffsetPx);
        }

        @Override
        protected void onTargetFound(@NonNull View targetView,
                                     @NonNull RecyclerView.State state,
                                     @NonNull Action action) {
            int row = targetPosition / Math.max(1, spanCount);
            int dy = Math.round(row * scrollStepPx - scrollOffsetPx);
            int duration = Math.max(240, Math.min(420, calculateTimeForDeceleration(Math.abs(dy))));
            if (dy != 0) {
                action.update(0, dy, duration, mDecelerateInterpolator);
            }
        }
    }

    private static final class SavedState implements Parcelable {
        float rowOffset;

        SavedState() {
        }

        SavedState(Parcel in) {
            rowOffset = in.readFloat();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeFloat(rowOffset);
        }

        public static final Creator<SavedState> CREATOR = new Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel source) {
                return new SavedState(source);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
