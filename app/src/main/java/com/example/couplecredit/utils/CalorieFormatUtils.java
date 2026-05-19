package com.example.couplecredit.utils;

import android.graphics.Color;

import java.util.Locale;

public class CalorieFormatUtils {
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_AUTO = "auto";

    private static final int COLOR_MANUAL = Color.parseColor("#F57C00");
    private static final int COLOR_AUTO = Color.parseColor("#2E7D32");
    private static final int COLOR_UNKNOWN = Color.parseColor("#999999");

    private CalorieFormatUtils() {
    }

    public static String formatCalories(Double totalCalories, String calorieSource) {
        if (totalCalories == null) {
            return "热量待补";
        }
        String prefix = SOURCE_MANUAL.equals(calorieSource) ? "估 " : "";
        if (Math.floor(totalCalories) == totalCalories) {
            return prefix + (totalCalories.longValue()) + " kcal";
        }
        return prefix + String.format(Locale.getDefault(), "%.1f kcal", totalCalories);
    }

    public static int resolveCalorieColor(Double totalCalories, String calorieSource) {
        if (totalCalories == null) {
            return COLOR_UNKNOWN;
        }
        return SOURCE_MANUAL.equals(calorieSource) ? COLOR_MANUAL : COLOR_AUTO;
    }

    public static String formatNumber(double value) {
        return formatWithDecimals(value, 1);
    }

    public static String formatDecimal(double value) {
        return formatWithDecimals(value, 2);
    }

    private static String formatWithDecimals(double value, int decimals) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%." + decimals + "f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
