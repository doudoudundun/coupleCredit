package com.example.couplecredit.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.couplecredit.utils.DateTimeUtils;

import org.junit.Test;

public class InventoryDateLogicTest {

    @Test
    public void normalizeDateTimeToUtc8_convertsUtcTimestampToAsiaShanghai() {
        String normalized = DateTimeUtils.normalizeDateTimeToUtc8("2026-04-21T16:30:00.000Z");

        assertEquals("2026-04-22 00:30:00", normalized);
    }

    @Test
    public void expirationInputIsOptional_allowsSavingWithoutShelfLife() {
        assertTrue(InventoryFragment.isExpirationInputValid(false, null, null, null));
    }
}
