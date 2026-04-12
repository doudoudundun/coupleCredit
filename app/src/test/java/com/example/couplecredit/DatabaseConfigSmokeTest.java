package com.example.couplecredit;

import com.example.couplecredit.config.DatabaseConfig;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatabaseConfigSmokeTest {
    @Test
    public void dbConfigPointsAtPrivateDatabase() {
        assertEquals("couple_credit_private", DatabaseConfig.DB_NAME);
        assertEquals("couple_app", DatabaseConfig.DB_USER);
        assertFalse(DatabaseConfig.DB_URL.contains("101.37.68.240"));
        assertTrue(DatabaseConfig.DB_URL.contains(DatabaseConfig.DB_NAME));
    }
}
