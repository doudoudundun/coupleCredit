package com.example.couplecredit.adapter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TodoAdapterRepeatLogicTest {

    @Test
    public void duplicateAction_isShownForDoneRepeatableTodo() {
        assertTrue(TodoAdapter.shouldShowDuplicateAction(true, "done"));
    }

    @Test
    public void duplicateAction_isHiddenForMissedRepeatableTodo() {
        assertFalse(TodoAdapter.shouldShowDuplicateAction(true, "missed"));
    }

    @Test
    public void duplicateAction_isHiddenForNonRepeatableTodo() {
        assertFalse(TodoAdapter.shouldShowDuplicateAction(false, "done"));
    }
}
