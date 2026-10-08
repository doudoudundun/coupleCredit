package com.example.couplecredit.view;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/** Rear cards are navigation targets, never shortcuts to obscured business actions. */
public final class InventoryDeckRecyclerView extends RecyclerView {
    private final Matrix inverse = new Matrix();
    private final float[] point = new float[2];
    private final int touchSlop;
    private boolean rearGesture;
    private boolean moved;
    private float downX;
    private float downY;
    private int targetPosition = NO_POSITION;

    public InventoryDeckRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        LayoutManager manager = getLayoutManager();
        if (!(manager instanceof InventoryDeckLayoutManager)) return super.dispatchTouchEvent(event);
        InventoryDeckLayoutManager deck = (InventoryDeckLayoutManager) manager;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            rearGesture = false;
            moved = false;
            targetPosition = NO_POSITION;
            View child = findTransformedCard(event.getX(), event.getY());
            int position = child == null ? NO_POSITION : getChildAdapterPosition(child);
            boolean settledFront = position != NO_POSITION && deck.isItemFocused(position)
                    && Math.abs(deck.getScrollRowOffset() - position / deck.getSpanCount()) < 0.03f;
            if (!settledFront) {
                rearGesture = true;
                targetPosition = position;
                downX = event.getX();
                downY = event.getY();
            }
        }
        if (!rearGesture) return super.dispatchTouchEvent(event);

        // Keep RecyclerView's own velocity tracking and drag/fling machinery, but
        // never send this gesture's DOWN to a covered card's consume/menu buttons.
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN
                || Math.abs(event.getX() - downX) > touchSlop
                || Math.abs(event.getY() - downY) > touchSlop) moved = true;
        super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) {
            int position = targetPosition;
            boolean tap = !moved;
            rearGesture = false;
            targetPosition = NO_POSITION;
            if (tap && position != NO_POSITION && getAdapter() != null
                    && position < getAdapter().getItemCount()) {
                stopScroll();
                smoothScrollToPosition(position);
                performClick();
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            rearGesture = false;
            targetPosition = NO_POSITION;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private View findTransformedCard(float x, float y) {
        View hit = null;
        float highestZ = -Float.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE || child.getAlpha() < 0.1f) continue;
            if (!child.getMatrix().invert(inverse)) continue;
            point[0] = x - child.getLeft();
            point[1] = y - child.getTop();
            inverse.mapPoints(point);
            if (point[0] >= 0 && point[0] < child.getWidth()
                    && point[1] >= 0 && point[1] < child.getHeight()
                    && child.getZ() >= highestZ) {
                hit = child;
                highestZ = child.getZ();
            }
        }
        return hit;
    }
}
