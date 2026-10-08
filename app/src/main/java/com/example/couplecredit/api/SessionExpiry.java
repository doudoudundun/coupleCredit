package com.example.couplecredit.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 JWT 的 exp 声明。只解析不验签——验签是服务端的职责，
 * 客户端只需要知道「该不该提前续期」。
 */
public final class SessionExpiry {
    private static final Pattern EXP = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    private SessionExpiry() {}

    /** 过期时间的 epoch 毫秒；无法解析返回 null。 */
    public static Long expiryEpochMs(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher m = EXP.matcher(payload);
            if (!m.find()) return null;
            return Long.parseLong(m.group(1)) * 1000L;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** true = 已过期或剩余有效期不足 windowMs；解析失败一律 false（交给 401 被动续期兜底）。 */
    public static boolean expiresWithin(String jwt, long nowMs, long windowMs) {
        Long expMs = expiryEpochMs(jwt);
        return expMs != null && expMs - nowMs <= windowMs;
    }
}
