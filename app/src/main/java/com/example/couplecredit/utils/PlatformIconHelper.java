package com.example.couplecredit.utils;

import android.content.Context;

/**
 * 平台图标匹配工具：根据平台名返回对应的内置 drawable 资源 id。
 *
 * 匹配规则（按优先级）：
 *   1. 中文名精确匹配（如"微信"）
 *   2. 英文名/常见别名匹配（如"WeChat"/"wechat"，大小写不敏感）
 *   3. 域名关键词匹配（如输入含"taobao"）
 *   4. 都不匹配返回 0，由调用方用首字母彩色头像兜底
 *
 * 如需替换为真实 logo：在 res/drawable 放入同名 png（如 ic_platform_wechat.png）即可，
 * vector XML 会被同名 png 覆盖，无需改动本类。
 */
public final class PlatformIconHelper {

    private PlatformIconHelper() {
    }

    /**
     * 根据平台名获取图标资源 id。未匹配返回 0。
     */
    public static int getIconResForPlatform(Context context, String name) {
        if (name == null) return 0;
        String n = name.trim();
        if (n.isEmpty()) return 0;
        String lower = n.toLowerCase();

        String resName = resolveResName(n, lower);
        if (resName == null) return 0;
        return context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
    }

    /**
     * 根据平台名计算一个稳定的背景色（用于首字母头像兜底）。返回 ARGB 颜色值。
     */
    public static int getColorForPlatform(String name) {
        if (name == null || name.isEmpty()) return 0xFF9E9E9E;
        int hash = name.hashCode();
        // 一组明快、辨识度高的品牌色
        int[] palette = {
                0xFF07C160, 0xFF1296DB, 0xFFE6162D, 0xFFFF6600, 0xFFFFC107,
                0xFF8BC34A, 0xFF00BCD4, 0xFFFF4081, 0xFF7C4DFF, 0xFF3F51B5,
                0xFF009688, 0xFFE91E63, 0xFF607D8B, 0xFFCDDC39, 0xFFFF5722
        };
        return palette[Math.abs(hash) % palette.length];
    }

    /**
     * 取平台名首字符用于头像显示（中文取首字，英文取首字母大写）。
     */
    public static String getInitial(String name) {
        if (name == null || name.isEmpty()) return "?";
        return String.valueOf(name.trim().charAt(0)).toUpperCase();
    }

    private static String resolveResName(String n, String lower) {
        switch (n) {
            case "微信": return "ic_platform_wechat";
            case "支付宝": case "阿里支付": return "ic_platform_alipay";
            case "QQ": case "qq": return "ic_platform_qq";
            case "淘宝": return "ic_platform_taobao";
            case "天猫": return "ic_platform_tmall";
            case "京东": return "ic_platform_jd";
            case "拼多多": return "ic_platform_pdd";
            case "美团": return "ic_platform_meituan";
            case "饿了么": return "ic_platform_eleme";
            case "抖音": return "ic_platform_douyin";
            case "快手": return "ic_platform_kuaishou";
            case "小红书": return "ic_platform_xhs";
            case "微博": return "ic_platform_weibo";
            case "哔哩哔哩": case "B站": case "b站": return "ic_platform_bili";
            case "百度": return "ic_platform_baidu";
            case "网易": case "网易邮箱": case "163邮箱": return "ic_platform_netease";
            case "知乎": return "ic_platform_zhihu";
            case "滴滴": case "滴滴出行": return "ic_platform_didi";
            default: break;
        }
        // 英文/别名/域名关键词匹配（大小写不敏感）
        if (containsAny(lower, "wechat", "weixin")) return "ic_platform_wechat";
        if (containsAny(lower, "alipay")) return "ic_platform_alipay";
        if (containsAny(lower, "tencent", "腾讯")) return "ic_platform_qq";
        if (containsAny(lower, "taobao", "淘宝")) return "ic_platform_taobao";
        if (containsAny(lower, "tmall", "天猫")) return "ic_platform_tmall";
        if (containsAny(lower, "jingdong", "京东") || lower.equals("jd") || lower.contains("jd.com"))
            return "ic_platform_jd";
        if (containsAny(lower, "pinduoduo", "拼多多")) return "ic_platform_pdd";
        if (containsAny(lower, "meituan", "美团")) return "ic_platform_meituan";
        if (containsAny(lower, "eleme", "饿了么")) return "ic_platform_eleme";
        if (containsAny(lower, "douyin", "抖音", "tiktok")) return "ic_platform_douyin";
        if (containsAny(lower, "kuaishou", "快手", "kwai")) return "ic_platform_kuaishou";
        if (containsAny(lower, "xiaohongshu", "小红书")) return "ic_platform_xhs";
        if (containsAny(lower, "weibo", "微博", "sina", "新浪")) return "ic_platform_weibo";
        if (containsAny(lower, "bilibili", "哔哩", "b站")) return "ic_platform_bili";
        if (containsAny(lower, "baidu", "百度")) return "ic_platform_baidu";
        if (containsAny(lower, "netease", "网易", "126")) return "ic_platform_netease";
        if (containsAny(lower, "zhihu", "知乎")) return "ic_platform_zhihu";
        if (containsAny(lower, "didi", "滴滴")) return "ic_platform_didi";
        return null;
    }

    private static boolean containsAny(String src, String... keys) {
        for (String k : keys) {
            if (src.contains(k)) return true;
        }
        return false;
    }
}
