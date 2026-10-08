# 重点修复与交互优化规格

依据 `PROJECT_AUDIT_20260913.md`，本轮修复全部 12 个 P1，以及 B05–B11、C01–C09，共 28 项。B01–B04（消息发送幂等、离线删除补偿、历史分页、点赞缓存）保留为后续聊天同步专项，不混入本轮验收结论。

## 必须保持的边界

- 以当前含 130 项未提交状态的磁盘内容为基线，保护原有修改。
- 三个写入 worker 必须在各自独立 worktree 工作；只集成明确归属文件相对基线的补丁。
- 不改生产数据库、不部署、不轮换生产密钥；真实日常账号只做读取与页面检查。
- Android 使用当前工作区重新构建安装，保留应用数据。数据库写入和安全异常测试使用隔离实例。
- 不削弱权限检查，不吞掉失败，不把错误响应伪装为成功；不为兼容旧未分类 JWT 而继续接受 token 类型混用。

## 分工与验收

### 数据与业务 worker

归属：server/src/routes/{bills,aiChat,recipes,inventory,assets,push}.js，及对应新增/原有服务端测试；可新增仅此模块使用的验证工具。不要修改 index/auth/chat/beads、迁移或 Android。

- A09/A10：账单编辑金额/方向、删除和计划余额在同一事务正确更新；并发重复删除不重复退款。100→支出10→90→编辑20→80→删除→100；双删除仍为100。
- A11：同一 extraction 只能创建一条业务记录，重复确认返回原 targetId，失败全部回滚；不能只依赖进程内互斥。
- A12：菜谱主表、食材、热量写入原子化，先验证输入。异常新增不留下孤立主表，异常编辑保留原内容。
- B08：筛选查询不污染无筛选资产缓存。
- B09：账单创建、修改、AI确认统一校验金额及 incomeType ∈ {0,1}。
- B10：物资事务提交成功后才失效缓存、发送成功响应。
- B11：删除推送 token 必须限制当前用户归属。

### 网络与安全 worker

归属：server/src/{index.js,middleware/auth.js,middleware/rateLimit.js,utils/jwt.js,routes/auth.js,routes/beads.js}、其新增安全工具、server/migrations/012*、新会话版本迁移、相应测试。不要修改数据 worker 文件、chat.js 或 Android。

- A03：明确 access/refresh 用途；改密、删号能撤销会话，业务鉴权验证有效用户/会话。需要新增数据库字段时提供后续迁移和部署顺序，不写生产。
- A04：AI限流只覆盖实际昂贵接口，普通 me/assets/extractions 查询不误限流，ai-chat不重复计数；inventory 生图可在 index 的具体路径前置限流，避免修改 inventory.js。
- A05：图片下载校验协议、解析 IP、重定向及总跳数，防止 DNS 重绑定；正常受控上传图片仍可识别。用隔离/注入测试证明拒绝 loopback、private/link-local/IPv6内网及重定向绕过。
- A07：修复 012 同表合并 SQL 歧义，保持原本合并语义；新旧色号、空库、重试可执行。

### Android 交互 worker

归属：Android activity/fragment/viewmodel/utils 与 XML，以及 InventoryAdapter、ApiConfigManager；排除 AuthApiClient/AuthApiModels/UserInfoManager/ChatRepository/CloudChatRepository 和数据库实体。重点 MainActivity/MyFragment/InventoryFragment/AssetsActivity/ReportFragment/ClassicModelFragment/CalorieActivity/PeriodActivity/UserSettingsActivity/PollingManager；允许新增 UI 辅助类。不要运行 Gradle/adb，主线程统一构建真机验收。

- B07：tab 返回主动刷新“我”；后台/子页返回后恢复当前轮询动作，避免重复轮询。
- C01/C04：独立 Activity 正确处理系统栏 inset 和图标亮暗；返回手势从出去吃返回菜谱。
- C02：首次/筛选/月份切换的报表标题、类型、结余统一更新。
- C03：在约359dp屏宽下库存名称、日期可辨识，按钮不遮挡操作；保留现有视觉风格，采用自适应信息密度。
- C05/C06：非法金额只提示不崩溃；AI 远程图片地址直接保存，本地图片才压缩上传。
- C07/C08：空列表清除旧卡片；筛选最终选中态与响应一致，旧响应不能覆盖新筛选，节流不得丢弃最终请求。
- C09：已有自定义服务器时测试连接仍实际执行，测试不应意外永久改变设置。
- 不简单删除失败的库存布局测试；若布局规格更新，改为验证当前明确行为。

### 主线程

归属：server/src/routes/chat.js 与相关测试；Android api/AuthApiClient.java、AuthApiModels.java、utils/UserInfoManager.java 及新增兼容工具/契约测试；规格和交付文档。集成阶段负责适配跨模块测试的事务 mock，并保留原权限断言。

- A01：Gson 同时读取旧服务器 0/1 与新服务器 boolean，服务端输出明确布尔值；列表/搜索都覆盖。
- A02：聊天发送严格比较请求关系与当前关系，禁止旧关系消息落入新关系。
- A06：所有 URL 编码调用兼容 minSdk26。
- A08：兼容线上旧概览结构或明确按接口回退，缺字段不能继续显示旧缓存成功态。
- B05/B06：短暂 refresh 故障保留登录态、并发刷新只执行一次并防止旧会话覆盖；图片上传走同样刷新策略且输入可重放。
- 集成前核查各 worker 文件边界、变更与验收证据；未达标重新派发。

## 完成交付标准

服务端相关行为测试与隔离 MySQL 回归通过；完整顺序迁移通过；Android build/JUnit 完成，明确记录 Lint 范围；安装最新 APK 并检查真实页面与只读网络。逐项记录已修复、已验证和剩余边界，不以构建成功代替功能验收。
