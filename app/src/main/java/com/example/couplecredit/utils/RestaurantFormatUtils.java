package com.example.couplecredit.utils;

public class RestaurantFormatUtils {
    public static String formatDistance(Double distance) {
        if (distance == null || distance <= 0) {
            return "距家未知";
        }
        if (distance >= 1000) {
            return String.format("距家 %.1fkm", distance / 1000);
        }
        return String.format("距家 %.0fm", distance);
    }

    public static String formatAvgCost(Double avgCost) {
        if (avgCost == null || avgCost <= 0) {
            return "人均未知";
        }
        return String.format("¥%.0f/人", avgCost);
    }
}
