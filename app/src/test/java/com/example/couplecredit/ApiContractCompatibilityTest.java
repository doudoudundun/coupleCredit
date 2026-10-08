package com.example.couplecredit;

import com.example.couplecredit.api.ApiEncoding;
import com.example.couplecredit.api.AuthApiModels;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class ApiContractCompatibilityTest {
    private final Gson gson = new Gson();

    @Test public void chatAcceptsOldNumbersAndNewBooleansInOneResponse() {
        AuthApiModels.ChatMessageListResponse response = gson.fromJson(
                "{\"ok\":true,\"data\":{\"messages\":[{\"is_liked\":0},{\"is_liked\":1},{\"is_liked\":false},{\"is_liked\":true}]}}",
                AuthApiModels.ChatMessageListResponse.class);
        assertEquals(4, response.data.messages.size());
        assertFalse(response.data.messages.get(0).isLiked);
        assertTrue(response.data.messages.get(1).isLiked);
        assertFalse(response.data.messages.get(2).isLiked);
        assertTrue(response.data.messages.get(3).isLiked);
    }

    @Test(expected = JsonParseException.class) public void corruptChatFlagsAreNotSilentlyAccepted() {
        gson.fromJson("{\"is_liked\":2}", AuthApiModels.ChatMessageData.class);
    }

    @Test public void oldOrPartialOverviewMustUseIndependentQueries() {
        AuthApiModels.OverviewResponse old = gson.fromJson(
                "{\"ok\":true,\"data\":{\"bills\":{\"ok\":true,\"data\":{}},\"todos\":{},\"inventory\":{}}}",
                AuthApiModels.OverviewResponse.class);
        assertFalse(old.data.hasCompleteSummaries());
        AuthApiModels.OverviewResponse current = gson.fromJson(
                "{\"ok\":true,\"data\":{\"billSummary\":{\"ok\":true,\"data\":{}},\"todoSummary\":{\"ok\":true,\"data\":{}},\"inventorySummary\":{\"ok\":true,\"data\":{}}}}",
                AuthApiModels.OverviewResponse.class);
        assertTrue(current.data.hasCompleteSummaries());
        current.data.inventorySummary.ok = false;
        assertFalse(current.data.hasCompleteSummaries());
    }

    @Test public void queryEncodingPreservesUnicodeAndEscapesQueryDelimiters() throws Exception {
        String input = "米 & 鸡蛋/牛奶+水果";
        String encoded = ApiEncoding.query(input);
        assertEquals(input, java.net.URLDecoder.decode(encoded, "UTF-8"));
        assertFalse(encoded.contains("&"));
        assertEquals("", ApiEncoding.query(null));
    }

    @Test public void securityCheckTextParsesPassAndRejectedResults() {
        AuthApiModels.SecurityCheckResponse degradedPass = gson.fromJson(
                "{\"ok\":true,\"message\":\"内容合规\",\"data\":{\"pass\":true,\"suggest\":\"pass\","
                        + "\"label\":null,\"degraded\":true,\"reason\":\"WX_CHECK_UNAVAILABLE\"}}",
                AuthApiModels.SecurityCheckResponse.class);
        assertTrue(degradedPass.data.pass);
        assertTrue(degradedPass.data.degraded);

        AuthApiModels.SecurityCheckResponse rejected = gson.fromJson(
                "{\"ok\":true,\"message\":\"不能包含联系方式\",\"data\":{\"pass\":false,\"suggest\":\"risky\","
                        + "\"degraded\":false,\"reason\":\"CONTACT_NOT_ALLOWED\"}}",
                AuthApiModels.SecurityCheckResponse.class);
        assertFalse(rejected.data.pass);
        assertEquals("不能包含联系方式", rejected.message);
    }

    @Test public void avatarStatusParsesModerationStates() {
        AuthApiModels.AvatarStatusResponse pending = gson.fromJson(
                "{\"ok\":true,\"data\":{\"avatarStatus\":\"pending\",\"shouldNotify\":true}}",
                AuthApiModels.AvatarStatusResponse.class);
        assertEquals("pending", pending.data.avatarStatus);
        assertTrue(pending.data.shouldNotify);

        AuthApiModels.AvatarStatusResponse approved = gson.fromJson(
                "{\"ok\":true,\"data\":{\"avatarStatus\":\"approved\",\"shouldNotify\":false}}",
                AuthApiModels.AvatarStatusResponse.class);
        assertEquals("approved", approved.data.avatarStatus);
        assertFalse(approved.data.shouldNotify);
    }

    @Test public void avatarUpdateSurfacesModerationStatus() {
        AuthApiModels.AvatarUpdateResponse response = gson.fromJson(
                "{\"ok\":true,\"message\":\"头像已提交审核\",\"data\":{\"avatarStatus\":\"pending\","
                        + "\"degraded\":false,\"reason\":null}}",
                AuthApiModels.AvatarUpdateResponse.class);
        assertTrue(response.ok);
        assertEquals("pending", response.data.avatarStatus);
        assertEquals("头像已提交审核", response.message);
    }

    @Test public void billDataParsesPhotosAndReceiptsAndToleratesLegacyRows() {
        AuthApiModels.BillData withImages = gson.fromJson(
                "{\"billId\":7,\"photos\":[\"/uploads/a.jpg\"],\"receipts\":[\"/uploads/b.jpg\"]}",
                AuthApiModels.BillData.class);
        assertEquals(1, withImages.photos.size());
        assertEquals("/uploads/b.jpg", withImages.receipts.get(0));

        // 历史账单没有图片字段
        AuthApiModels.BillData legacy = gson.fromJson("{\"billId\":8}", AuthApiModels.BillData.class);
        assertNull(legacy.photos);
        assertNull(legacy.receipts);
    }

    @Test public void updateBillRequestOmitsNullImagesAndSendsEmptyArrayToClear() {
        // 不传图片字段 → 服务端 hasOwnProperty 为 false → 保留原图
        String keepJson = gson.toJson(new AuthApiModels.UpdateBillRequest(1, "t", "餐品", 10, "2026-09-24", "12:00:00", 0));
        assertFalse(keepJson.contains("photos"));
        assertFalse(keepJson.contains("receipts"));

        // 空数组 → 明确传 [] → 服务端清空
        String clearJson = gson.toJson(new AuthApiModels.UpdateBillRequest(1, "t", "餐品", 10, "2026-09-24", "12:00:00", 0,
                new ArrayList<>(), new ArrayList<>()));
        assertTrue(clearJson.contains("\"photos\":[]"));
        assertTrue(clearJson.contains("\"receipts\":[]"));

        // 创建请求带上图片
        String createJson = gson.toJson(new AuthApiModels.CreateBillRequest(1, "自己", null, "t", "餐品", 10, "2026-09-24", "12:00:00", 0,
                java.util.Arrays.asList("/uploads/a.jpg"), null));
        assertTrue(createJson.contains("\"photos\":[\"/uploads/a.jpg\"]"));
        assertFalse(createJson.contains("receipts"));
    }
}
