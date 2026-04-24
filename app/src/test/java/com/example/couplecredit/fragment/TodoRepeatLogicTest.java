package com.example.couplecredit.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TodoRepeatLogicTest {

    @Test
    public void enablingRepeatOnExistingTodoWithoutSeries_assignsSeriesFromTodoId() {
        assertEquals(Long.valueOf(12L), TodoFragment.resolveSeriesIdForSave(12, null, true));
    }

    @Test
    public void disablingRepeat_clearsSeries() {
        assertNull(TodoFragment.resolveSeriesIdForSave(12, 12L, false));
    }

    @Test
    public void completingRepeatableTodo_incrementsCompletedCount() {
        assertEquals(4, TodoFragment.resolveCompletedCountForToggle("open", true, 3));
    }

    @Test
    public void completingNonRepeatableTodo_keepsCompletedCount() {
        assertEquals(3, TodoFragment.resolveCompletedCountForToggle("open", false, 3));
    }

    @Test
    public void completedRepeatableTodo_triggersAutoDuplicate() {
        assertTrue(TodoFragment.shouldAutoDuplicateAfterCompletion("open", true));
    }

    @Test
    public void completedNonRepeatableTodo_doesNotTriggerAutoDuplicate() {
        assertFalse(TodoFragment.shouldAutoDuplicateAfterCompletion("open", false));
    }
}
