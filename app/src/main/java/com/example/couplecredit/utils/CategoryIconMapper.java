package com.example.couplecredit.utils;

import com.example.couplecredit.R;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类图标映射工具类
 * 统一管理记账分类与图标的映射关系
 * 
 * 功能包括：
 * 1. 标准化的分类定义
 * 2. 分类与图标的映射
 * 3. 分类关键词匹配
 * 4. 支出/收入分类区分
 */
public class CategoryIconMapper {
    
    // ==================== 标准化分类定义 ====================
    
    /**
     * 支出分类列表
     */
    public static final List<String> EXPENSE_CATEGORIES = Arrays.asList(
        "餐品", "饮品", "水果", "购物", "交通", "住宿", "娱乐", "学习",
        "医疗", "日常", "旅游", "通讯", "人情", "化妆", "会员", "投资", 
        "亲子", "宠物", "装修", "其他"
    );
    
    /**
     * 收入分类列表
     */
    public static final List<String> INCOME_CATEGORIES = Arrays.asList(
        "工资", "兼职", "礼金", "理财", "其他"
    );
    
    // ==================== 分类图标映射 ====================
    
    /**
     * 分类图标映射表
     */
    private static final Map<String, Integer> CATEGORY_ICON_MAP = new HashMap<>();
    static {
        // 支出分类图标
        CATEGORY_ICON_MAP.put("餐品", R.drawable.img_category_food);
        CATEGORY_ICON_MAP.put("饮品", R.drawable.img_category_drink);
        CATEGORY_ICON_MAP.put("水果", R.drawable.img_category_fruit);
        CATEGORY_ICON_MAP.put("购物", R.drawable.img_category_shopping);
        CATEGORY_ICON_MAP.put("交通", R.drawable.img_category_transport);
        CATEGORY_ICON_MAP.put("住宿", R.drawable.img_category_hotel);
        CATEGORY_ICON_MAP.put("娱乐", R.drawable.img_category_entertainment);
        CATEGORY_ICON_MAP.put("学习", R.drawable.img_category_study);
        CATEGORY_ICON_MAP.put("医疗", R.drawable.img_category_medical);
        CATEGORY_ICON_MAP.put("日常", R.drawable.img_category_daily);
        CATEGORY_ICON_MAP.put("旅游", R.drawable.img_category_travel);
        CATEGORY_ICON_MAP.put("通讯", R.drawable.img_category_communication);
        CATEGORY_ICON_MAP.put("人情", R.drawable.img_category_social);
        CATEGORY_ICON_MAP.put("社交", R.drawable.img_category_social);
        CATEGORY_ICON_MAP.put("化妆", R.drawable.img_category_cosmetic);
        CATEGORY_ICON_MAP.put("会员", R.drawable.img_category_member);
        CATEGORY_ICON_MAP.put("投资", R.drawable.img_category_investment);
        CATEGORY_ICON_MAP.put("亲子", R.drawable.img_category_parenting);
        CATEGORY_ICON_MAP.put("育儿", R.drawable.img_category_parenting);
        CATEGORY_ICON_MAP.put("宠物", R.drawable.img_category_pet);
        CATEGORY_ICON_MAP.put("装修", R.drawable.img_category_decoration);
        
        // 收入分类图标
        CATEGORY_ICON_MAP.put("工资", R.drawable.img_category_salary);
        CATEGORY_ICON_MAP.put("兼职", R.drawable.img_category_parttime);
        CATEGORY_ICON_MAP.put("礼金", R.drawable.img_category_cashgift);
        CATEGORY_ICON_MAP.put("理财", R.drawable.img_category_financial);
        
        // 通用分类图标
        CATEGORY_ICON_MAP.put("生活", R.drawable.img_category_other);
        CATEGORY_ICON_MAP.put("其他", R.drawable.img_category_other);
    }
    
    // ==================== 分类关键词映射 ====================
    
    /**
     * 分类关键词映射表
     * 用于从文本中智能识别分类
     */
    private static final Map<String, String> CATEGORY_KEYWORDS = new HashMap<>();
    static {
        // 餐饮类
        CATEGORY_KEYWORDS.put("餐品", "早餐|午餐|晚餐|夜宵|吃饭|用餐|聚餐|外卖|点餐|饭店|餐厅|食堂|快餐|火锅|烧烤|自助餐|餐品");
        CATEGORY_KEYWORDS.put("饮品", "咖啡|奶茶|饮料|果汁|酒|啤酒|红酒|白酒|茶|水|可乐|雪碧|矿泉水");
        CATEGORY_KEYWORDS.put("水果", "水果|苹果|香蕉|橙子|葡萄|草莓|西瓜|芒果|猕猴桃|樱桃|荔枝|龙眼");
        
        // 购物类
        CATEGORY_KEYWORDS.put("购物", "购物|买|购买|商场|超市|网购|淘宝|京东|拼多多|衣服|鞋子|包包");
        
        // 交通类
        CATEGORY_KEYWORDS.put("交通", "打车|滴滴|出租车|公交|地铁|火车|高铁|飞机|机票|车票|加油|停车|过路费");
        
        // 住宿类
        CATEGORY_KEYWORDS.put("住宿", "住宿|酒店|宾馆|民宿|房租|水电费|物业费|网费|房费");
        
        // 娱乐类
        CATEGORY_KEYWORDS.put("娱乐", "电影|KTV|游戏|娱乐|唱歌|看电影|游乐园|演唱会|话剧|展览|博物馆");
        
        // 学习类
        CATEGORY_KEYWORDS.put("学习", "学习|培训|课程|书籍|文具|学费|培训费|考试费|报名费");
        
        // 医疗类
        CATEGORY_KEYWORDS.put("医疗", "医疗|看病|医院|药|体检|挂号费|医药费|治疗费|检查费");
        
        // 日常类
        CATEGORY_KEYWORDS.put("日常", "日用品|洗发水|牙膏|毛巾|纸巾|洗衣液|清洁用品|生活用品");
        
        // 旅游类
        CATEGORY_KEYWORDS.put("旅游", "旅游|旅行|景点|门票|导游|团费|签证|保险");
        
        // 通讯类
        CATEGORY_KEYWORDS.put("通讯", "话费|流量|宽带|手机费|电话费|网费|充值");
        
        // 人情类
        CATEGORY_KEYWORDS.put("人情", "红包|礼金|礼品|生日|结婚|满月|升学|乔迁|慰问|捐款|社交");
        
        // 化妆类
        CATEGORY_KEYWORDS.put("化妆", "化妆品|护肤品|口红|粉底|眼影|面膜|洗面奶|爽肤水|精华|乳液");
        
        // 会员类
        CATEGORY_KEYWORDS.put("会员", "会员|会员费|年费|月费|订阅|充值|VIP");
        
        // 投资类
        CATEGORY_KEYWORDS.put("投资", "投资|股票|基金|理财产品|保险|债券");
        
        // 亲子类
        CATEGORY_KEYWORDS.put("亲子", "育儿|亲子|儿童|玩具|奶粉|尿布|童装|早教|幼儿园");
        
        // 宠物类
        CATEGORY_KEYWORDS.put("宠物", "宠物|狗粮|猫粮|宠物用品|宠物医院|宠物美容");
        
        // 装修类
        CATEGORY_KEYWORDS.put("装修", "装修|家具|建材|油漆|瓷砖|地板|窗帘|灯具");
        
        // 收入类关键词
        CATEGORY_KEYWORDS.put("工资", "工资|薪水|薪资|月薪|年薪");
        CATEGORY_KEYWORDS.put("兼职", "兼职|外快|副业|临时工");
        CATEGORY_KEYWORDS.put("礼金", "礼金|红包|压岁钱|生日钱");
        CATEGORY_KEYWORDS.put("理财", "理财|理财收益|基金收益|银行利息");
    }
    
    // ==================== 公共方法 ====================
    
    /**
     * 获取分类对应的图标资源ID
     * @param category 分类名称
     * @return 图标资源ID，如果未找到返回默认图标
     */
    public static int getIconForCategory(String category) {
        Integer iconResId = CATEGORY_ICON_MAP.get(category);
        return iconResId != null ? iconResId : R.drawable.img_category_other;
    }
    
    /**
     * 根据收支类型获取分类列表
     * @param isExpense true为支出，false为收入
     * @return 分类列表
     */
    public static List<String> getCategoriesByType(boolean isExpense) {
        return isExpense ? EXPENSE_CATEGORIES : INCOME_CATEGORIES;
    }
    
    /**
     * 从文本中智能识别分类
     * @param text 待识别的文本
     * @return 识别到的分类，如果未识别到返回null
     */
    public static String extractCategoryFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        String lowerText = text.toLowerCase();
        
        // 遍历所有分类关键词进行匹配
        for (Map.Entry<String, String> entry : CATEGORY_KEYWORDS.entrySet()) {
            String category = entry.getKey();
            String keywords = entry.getValue().toLowerCase();
            
            // 将关键词按|分割，逐个匹配
            String[] keywordArray = keywords.split("\\|");
            for (String keyword : keywordArray) {
                if (lowerText.contains(keyword.trim())) {
                    return category;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 检查分类是否为支出分类
     * @param category 分类名称
     * @return true为支出分类，false为收入分类
     */
    public static boolean isExpenseCategory(String category) {
        return EXPENSE_CATEGORIES.contains(category);
    }
    
    /**
     * 检查分类是否为收入分类
     * @param category 分类名称
     * @return true为收入分类，false为支出分类
     */
    public static boolean isIncomeCategory(String category) {
        return INCOME_CATEGORIES.contains(category);
    }
    
    /**
     * 获取所有支出分类
     * @return 支出分类列表
     */
    public static List<String> getExpenseCategories() {
        return EXPENSE_CATEGORIES;
    }
    
    /**
     * 获取所有收入分类
     * @return 收入分类列表
     */
    public static List<String> getIncomeCategories() {
        return INCOME_CATEGORIES;
    }
    
    /**
     * 获取分类的关键词
     * @param category 分类名称
     * @return 关键词字符串，如果未找到返回空字符串
     */
    public static String getCategoryKeywords(String category) {
        String keywords = CATEGORY_KEYWORDS.get(category);
        return keywords != null ? keywords : "";
    }
}