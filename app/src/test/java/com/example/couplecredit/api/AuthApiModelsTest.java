package com.example.couplecredit.api;

import android.content.Context;

import com.example.couplecredit.BuildConfig;
import com.google.gson.Gson;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

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
                BuildConfig.PRIVATE_API_INVITE_CODE
        );

        String json = GSON.toJson(request);

        assertTrue(json.contains("\"username\":\"alice\""));
        assertTrue(json.contains("\"inviteCode\":\"" + BuildConfig.PRIVATE_API_INVITE_CODE + "\""));
    }

    @Test
    public void serializesCreateBillRequestWithBillOwner() {
        AuthApiModels.CreateBillRequest request = new AuthApiModels.CreateBillRequest(
                7,
                "自己",
                null,
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
    public void serializesCreateTodoRequestWithRepeatMetadata() {
        AuthApiModels.CreateTodoRequest request = new AuthApiModels.CreateTodoRequest(
                12,
                "倒猫粮",
                "晚上补一次",
                "medium",
                "今晚",
                null,
                "open",
                true,
                1001L,
                3
        );

        String json = GSON.toJson(request);

        assertTrue(json.contains("\"isRepeatable\":true"));
        assertTrue(json.contains("\"seriesId\":1001"));
        assertTrue(json.contains("\"completedCount\":3"));
    }

    @Test
    public void serializesCreateBeadBlueprintRequestWithQuantityPerBuild() {
        AuthApiModels.CreateBeadBlueprintRequest request = new AuthApiModels.CreateBeadBlueprintRequest(
                12,
                "星星挂件",
                null,
                Arrays.asList(
                        new AuthApiModels.BeadBlueprintColorRequest("C001", 12),
                        new AuthApiModels.BeadBlueprintColorRequest("T099", 6)
                )
        );

        String json = GSON.toJson(request);

        assertTrue(json.contains("\"userId\":12"));
        assertTrue(json.contains("\"name\":\"星星挂件\""));
        assertTrue(json.contains("\"colorCode\":\"C001\""));
        assertTrue(json.contains("\"quantityPerBuild\":12"));
        assertTrue(json.contains("\"colorCode\":\"T099\""));
        assertTrue(json.contains("\"quantityPerBuild\":6"));
        assertFalse(json.contains("\"quantity\":12"));
    }

    @Test
    public void serializesUpdateBeadInventoryRequest() {
        AuthApiModels.UpdateBeadInventoryRequest request = new AuthApiModels.UpdateBeadInventoryRequest(
                12,
                320,
                180
        );

        String json = GSON.toJson(request);

        assertTrue(json.contains("\"userId\":12"));
        assertTrue(json.contains("\"quantity\":320"));
        assertTrue(json.contains("\"thresholdOverride\":180"));
    }

    @Test
    public void authApiClientExposesCreateBillMethod() throws Exception {
        Method method = AuthApiClient.class.getMethod(
                "createBill",
                Context.class,
                int.class,
                String.class,
                Integer.class,
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
    public void authApiClientExposesBuildBeadBlueprintMethod() throws Exception {
        Method method = AuthApiClient.class.getMethod(
                "buildBeadBlueprint",
                Context.class,
                int.class,
                int.class,
                Integer.class,
                AuthApiClient.BuildBeadBlueprintCallback.class
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
    public void parsesBeadInventoryListResponse() {
        String json = "{\"ok\":true,\"data\":{\"relationshipId\":21,\"summary\":{\"totalColors\":2,\"lowStockCount\":1,\"totalConsumptionReference\":48},\"items\":[{\"colorCode\":\"C001\",\"hexColor\":\"#FFAA00\",\"quantity\":150,\"thresholdOverride\":180,\"defaultThreshold\":200,\"totalConsumed\":36,\"colorGroup\":\"warm\",\"isTransparent\":false,\"isLowStock\":true},{\"colorCode\":\"T099\",\"hexColor\":\"#FFFFFF\",\"quantity\":240,\"thresholdOverride\":null,\"defaultThreshold\":200,\"totalConsumed\":12,\"colorGroup\":\"clear\",\"isTransparent\":true,\"isLowStock\":false}]}}";

        AuthApiModels.BeadInventoryListResponse response = GSON.fromJson(json, AuthApiModels.BeadInventoryListResponse.class);

        assertTrue(response.ok);
        assertEquals(Integer.valueOf(21), response.data.relationshipId);
        assertEquals(2, response.data.summary.totalColors);
        assertEquals(1, response.data.summary.lowStockCount);
        assertEquals(48, response.data.summary.totalConsumptionReference);
        assertEquals(2, response.data.items.size());
        assertEquals("C001", response.data.items.get(0).colorCode);
        assertEquals(180, response.data.items.get(0).thresholdOverride.intValue());
        assertTrue(response.data.items.get(0).isLowStock);
        assertTrue(response.data.items.get(1).isTransparent);
    }

    @Test
    public void parsesBeadBlueprintDetailResponseWithAlternateQuantityField() {
        String json = "{\"ok\":true,\"data\":{\"blueprintId\":8,\"userId\":12,\"relationshipId\":21,\"name\":\"春日花束\",\"buildCount\":3,\"colorCount\":2,\"totalBeadsPerBuild\":18,\"totalConsumed\":54,\"createdAt\":\"2026-04-22T09:30:00.000Z\",\"updatedAt\":\"2026-04-23T10:00:00.000Z\",\"colors\":[{\"colorCode\":\"C001\",\"hexColor\":\"#FFAA00\",\"colorGroup\":\"warm\",\"isTransparent\":false,\"quantity\":10,\"totalConsumed\":30},{\"colorCode\":\"T099\",\"hexColor\":\"#FFFFFF\",\"colorGroup\":\"clear\",\"isTransparent\":true,\"quantityPerBuild\":8,\"totalConsumed\":24}]}}";

        AuthApiModels.BeadBlueprintResponse response = GSON.fromJson(json, AuthApiModels.BeadBlueprintResponse.class);

        assertTrue(response.ok);
        assertEquals(8, response.data.blueprintId);
        assertEquals(Integer.valueOf(21), response.data.relationshipId);
        assertEquals(2, response.data.colors.size());
        assertEquals(10, response.data.colors.get(0).quantityPerBuild);
        assertEquals(8, response.data.colors.get(1).quantityPerBuild);
        assertTrue(response.data.colors.get(1).isTransparent);
    }

    @Test
    public void parsesBuildBeadBlueprintResponseWithPlannedBuildCountShape() {
        String json = "{\"ok\":true,\"message\":\"记录串珠制作成功\",\"data\":{\"buildCount\":6}}";

        AuthApiModels.BuildBeadBlueprintResponse response = GSON.fromJson(json, AuthApiModels.BuildBeadBlueprintResponse.class);

        assertTrue(response.ok);
        assertEquals(6, response.data.buildCount);
        assertEquals(6, response.data.currentBuildCount);
    }

    @Test
    public void parsesBuildBeadBlueprintResponseWithLegacyShape() {
        String json = "{\"ok\":true,\"message\":\"记录串珠制作成功\",\"data\":{\"previousBuildCount\":4,\"addedCount\":2,\"currentBuildCount\":6}}";

        AuthApiModels.BuildBeadBlueprintResponse response = GSON.fromJson(json, AuthApiModels.BuildBeadBlueprintResponse.class);

        assertTrue(response.ok);
        assertEquals(6, response.data.buildCount);
        assertEquals(4, response.data.previousBuildCount);
        assertEquals(2, response.data.addedCount);
        assertEquals(6, response.data.currentBuildCount);
    }

    @Test
    public void normalizesStructuredApiErrorBody() throws Exception {
        Method method = AuthApiClient.class.getDeclaredMethod("normalizeErrorMessage", String.class);
        method.setAccessible(true);

        String raw = "{\"ok\":false,\"error\":{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"库存不足\"}}";

        assertEquals("库存不足", method.invoke(null, raw));
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
