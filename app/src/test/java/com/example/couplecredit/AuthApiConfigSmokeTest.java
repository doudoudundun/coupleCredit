package com.example.couplecredit;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuthApiConfigSmokeTest {
    @Test
    public void apiConfigUsesConfiguredHttpApi() {
        assertTrue(BuildConfig.PRIVATE_API_BASE_URL.startsWith("http://") || BuildConfig.PRIVATE_API_BASE_URL.startsWith("https://"));
        assertFalse(BuildConfig.PRIVATE_API_BASE_URL.contains(":3306"));
        assertFalse(BuildConfig.PRIVATE_API_INVITE_CODE.isEmpty());
    }
}
