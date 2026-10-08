package com.example.couplecredit;

import com.example.couplecredit.api.SessionExpiry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SessionExpiryTest {
    private static String jwt(String payloadJson) {
        return "x." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".y";
    }

    private static final long NOW = 1_800_000_000_000L; // 任意固定时刻
    private static final long WINDOW = 120_000L;

    @Test
    public void expiredTokenExpiresWithinWindow() {
        String token = jwt("{\"exp\":" + ((NOW - 1000) / 1000) + "}");
        assertTrue(SessionExpiry.expiresWithin(token, NOW, WINDOW));
    }

    @Test
    public void tokenExpiringInsideWindowExpiresWithin() {
        String token = jwt("{\"exp\":" + ((NOW + 60_000) / 1000) + "}");
        assertTrue(SessionExpiry.expiresWithin(token, NOW, WINDOW));
    }

    @Test
    public void freshTokenOutsideWindowDoesNotExpireWithin() {
        String token = jwt("{\"exp\":" + ((NOW + 600_000) / 1000) + "}");
        assertFalse(SessionExpiry.expiresWithin(token, NOW, WINDOW));
        assertEquals(Long.valueOf(NOW + 600_000), SessionExpiry.expiryEpochMs(token));
    }

    @Test
    public void unparseableTokensNeverExpireWithin() {
        assertFalse(SessionExpiry.expiresWithin(null, NOW, WINDOW));
        assertFalse(SessionExpiry.expiresWithin("", NOW, WINDOW));
        assertFalse(SessionExpiry.expiresWithin("not-a-jwt", NOW, WINDOW));
        assertFalse(SessionExpiry.expiresWithin("a.!!!.b", NOW, WINDOW));
        assertFalse(SessionExpiry.expiresWithin(jwt("{}"), NOW, WINDOW));
        assertFalse(SessionExpiry.expiresWithin(jwt("{\"exp\":\"soon\"}"), NOW, WINDOW));
        assertNull(SessionExpiry.expiryEpochMs(null));
    }

    @Test
    public void exactBoundaryExpiresWithin() {
        String token = jwt("{\"exp\":" + ((NOW + WINDOW) / 1000) + "}");
        assertTrue(SessionExpiry.expiresWithin(token, NOW, WINDOW));
    }
}
