package com.example.couplecredit.api;

import com.google.gson.Gson;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AuthApiModelsTest {
    private static final Gson GSON = new Gson();

    @Test
    public void serializesRegisterRequestWithInviteCode() {
        AuthApiModels.RegisterRequest request = new AuthApiModels.RegisterRequest(
                "alice",
                "alice@example.com",
                "secret123",
                "COUPLE-PRIVATE-2026"
        );

        String json = GSON.toJson(request);

        assertTrue(json.contains("\"username\":\"alice\""));
        assertTrue(json.contains("\"inviteCode\":\"COUPLE-PRIVATE-2026\""));
    }

    @Test
    public void serializesCreateBillRequestWithBillOwner() {
        AuthApiModels.CreateBillRequest request = new AuthApiModels.CreateBillRequest(
                7,
                "自己",
                "午餐",
                "餐饮",
                25.5,
                "2026-04-12",
                "12:30:00",
                0
        );

        String json = GSON.toJson(request);

        assertTrue(json.contains("\"userId\":7"));
        assertTrue(json.contains("\"billOwner\":\"自己\""));
        assertTrue(json.contains("\"title\":\"午餐\""));
        assertFalse(json.contains("relationshipId"));
        assertFalse(json.contains("owner"));
    }

    @Test
    public void authApiClientExposesCreateBillMethod() throws Exception {
        Method method = AuthApiClient.class.getMethod(
                "createBill",
                int.class,
                String.class,
                String.class,
                String.class,
                double.class,
                String.class,
                String.class,
                int.class,
                AuthApiClient.BillCallback.class
        );

        assertNotNull(method);
    }

    @Test
    public void parsesLoginSuccessResponse() {
        String json = "{\"ok\":true,\"message\":\"登录成功\",\"data\":{\"userId\":12,\"username\":\"alice\",\"email\":\"alice@example.com\"}}";

        AuthApiModels.AuthResponse response = GSON.fromJson(json, AuthApiModels.AuthResponse.class);

        assertTrue(response.ok);
        assertEquals(12, response.data.userId);
        assertEquals("alice", response.data.username);
        assertEquals("alice@example.com", response.data.email);
    }

    @Test
    public void parsesCreateBillSuccessResponse() {
        String json = "{\"ok\":true,\"message\":\"账单创建成功\",\"data\":{\"billId\":33,\"relationshipId\":9,\"owner\":2,\"userId\":12,\"title\":\"午餐\",\"type\":\"餐饮\",\"amount\":25.5,\"date\":\"2026-04-12\",\"time\":\"12:30:00\",\"incomeType\":0,\"isHelp\":1}}";

        AuthApiModels.BillResponse response = GSON.fromJson(json, AuthApiModels.BillResponse.class);

        assertTrue(response.ok);
        assertEquals(33L, response.data.billId);
        assertEquals(Integer.valueOf(9), response.data.relationshipId);
        assertEquals(2, response.data.owner);
        assertEquals(12, response.data.userId);
        assertEquals(1, response.data.isHelp);
    }

    @Test
    public void parsesErrorResponse() {
        String json = "{\"ok\":false,\"error\":{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"用户名或密码错误\"}}";

        AuthApiModels.AuthResponse response = GSON.fromJson(json, AuthApiModels.AuthResponse.class);

        assertFalse(response.ok);
        assertEquals("INVALID_CREDENTIALS", response.error.code);
        assertEquals("用户名或密码错误", response.error.message);
    }
}
