package com.example.couplecredit.utils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class DateTimeUtils {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private DateTimeUtils() {
    }

    public static String normalizeDateTimeToUtc8(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (hasOffset(trimmed)) {
                String candidate = trimmed.replace(' ', 'T');
                if (candidate.endsWith("z")) {
                    candidate = candidate.substring(0, candidate.length() - 1) + "Z";
                }
                Instant instant = OffsetDateTime.parse(candidate).toInstant();
                return DISPLAY_FORMATTER.withZone(DISPLAY_ZONE).format(instant);
            }
        } catch (DateTimeParseException ignored) {
        }

        String normalized = trimmed.replace('T', ' ');
        int dotIndex = normalized.indexOf('.');
        if (dotIndex > 0) {
            normalized = normalized.substring(0, dotIndex);
        }
        if (normalized.endsWith("Z") || normalized.endsWith("z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean hasOffset(String value) {
        return value.endsWith("Z")
                || value.endsWith("z")
                || value.matches(".*[+-]\\d{2}:\\d{2}$");
    }
}
