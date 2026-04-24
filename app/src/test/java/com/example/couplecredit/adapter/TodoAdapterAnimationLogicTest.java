package com.example.couplecredit.adapter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TodoAdapterAnimationLogicTest {

    @Test
    public void statusTransition_keepsExpandedContentHiddenDuringCompletionAnimation() {
        assertFalse(TodoAdapter.shouldShowExpandedContentDuringStatusTransition(true));
    }

    @Test
    public void normalBind_stillShowsExpandedContentWhenExpanded() {
        assertTrue(TodoAdapter.shouldShowExpandedContentDuringStatusTransition(false));
    }

    @Test
    public void completedItem_doesNotRestoreExpandedStateAfterStatusTransition() {
        assertFalse(TodoAdapter.shouldRestoreExpandedStateAfterCompletion(true, true));
    }

    @Test
    public void pendingCompletion_hidesDoneItemUntilRefreshFinishes() {
        assertFalse(TodoAdapter.shouldKeepCompletedItemVisible(0, "done", true));
    }

    @Test
    public void pendingCompletion_hidesOpenItemUntilRefreshFinishes() {
        assertFalse(TodoAdapter.shouldKeepCompletedItemVisible(0, "open", true));
    }

    @Test
    public void nonPendingCompletion_keepsOpenItemVisible() {
        assertTrue(TodoAdapter.shouldKeepCompletedItemVisible(0, "open", false));
    }
}
