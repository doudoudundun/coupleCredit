package com.example.couplecredit.fragment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class AddBillDateLogicTest {

    @Test
    public void syncSelectedDateWithToday_replacesStaleDay() {
        Calendar selectedDate = Calendar.getInstance();
        selectedDate.set(2026, Calendar.APRIL, 21, 9, 0, 0);
        selectedDate.set(Calendar.MILLISECOND, 0);

        Date now = new Date(1776787200000L); // 2026-04-22 08:00:00 +08:00

        AddBillFragment.syncSelectedDateWithToday(selectedDate, now);

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        assertEquals("2026-04-22", format.format(selectedDate.getTime()));
    }
}
