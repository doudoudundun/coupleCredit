# CoupleCredit 全面体检报告

审查日期：2026-09-13。审查对象：当前磁盘工作区（HEAD `d53bd8b`，开始时已有 129 项未提交状态），不是仅审查最近一次提交。

本报告区分真机/接口实测、隔离环境复现、静态代码结论。发现项不等同于已经修复；本轮没有修改业务源码、部署线上服务或提交真实业务数据。

## 检查方法与结果

| 检查层 | 本轮结果 | 证据边界 |
| --- | --- | --- |
| Android 构建 | `:app:assembleDebug` 通过 | 当前 APK 已通过 `adb install -r` 安装，未清数据；手机 APK SHA-256 与本地一致 |
| Android 单元检查 | 58 项，54 通过、4 失败 | 失败集中于库存 XML 结构预期；不能直接当作 4 个独立功能缺陷 |
| Android 标准 Lint | 未完成 | 约 9 分钟停留在旧 Fragment 检查器递归访问，保留线程栈后终止 |
| Android 其余 Lint | 42 errors、1388 warnings | 仅本次用临时 init script 禁用 `FragmentLiveDataObserve`、`FragmentBackPressedCallback`；不是完整 Lint 验收 |
| 服务端完整测试 | **104/104 通过** | 独立 MySQL 8.0.33 + 当前服务端源码快照；未连接真实业务库 |
| 新库建表/迁移 | 初始化成功，012 失败，其余迁移逐个执行成功 | 012 的 SQL 错误没有被上述测试发现；不是整套顺序迁移通过 |
| 真机 | Android 16、1256×2760、560 dpi | 浏览首页、物资、菜谱、出去吃、待办、聊天、计划、报表、个人概览、资产、热量、经期；仅当前设备 |
| 线上查询链路 | 健康接口 200、TLS 校验成功；实际业务查询可返回 | 200 并不保证 Android 解析成功，也不证明线上源码与工作区一致 |
| 自然过期的登录会话 | 401 → refresh 成功 → 原请求重试 200 | 真机正常刷新路径已验证；异常刷新、并发刷新另见问题项 |
| 工作区保护 | 原有业务文件未改动 | 本轮仅新增审查文档；原手机 APK 已备份于本次临时证据目录 |

原始检查记录、脱敏网络记录及隔离复现脚本位于 `/tmp/couplecredit-audit-20260913/`。真实账号截图和日志没有复制进项目文档。当前安装包 SHA-256：`963a38f2b14f00a77493947b349f0c5b547e2789abf317a5b4d6c376a7e62858`。

收尾时已停止本轮隔离 API、MySQL 与日志采集进程，保留本机复核证据（金额/缓存专项结果为 `data-recheck-results.json`）。原有 8082 服务未操作；工作区状态由 129 项变为 130 项，新增项为本报告，`git diff --check` 通过。

严重度：P1 表示应优先阻止发布或尽快修复的核心功能、安全、数据或部署问题；P2 表示明确影响功能正确性、恢复能力或可用性的缺陷。没有证据支持把本轮发现标为 P0。

本轮列出 **32 个确认发现项：12 个 P1、20 个 P2**。其中同时包含动态复现和有完整调用链的静态结论，各项均标明证据类型；这不是“项目已无其他问题”的保证。三个只读审查分工覆盖服务端网络/安全、数据/业务、Android/UI；主线程完成构建、真机检查及关键结果复核，金额、并发删除和资产缓存还经过重新派发的专项复现。

## 优先处理的问题

### A01 · P1 · 聊天消息接口 200，但 Android 整批解析失败

- **实测**：真机进入聊天后为空白，日志出现 `GET /api/chat/messages ... status=200`，随后连续出现“聊天消息响应异常”，最终回退本地空缓存。
- **根因**：[chat.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/chat.js:67) 直接返回 MySQL 行，`is_liked` 是数字 0/1；[AuthApiModels.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/api/AuthApiModels.java:828) 声明为 boolean。项目实际 Gson 2.10.1 对 0、1 均抛 `Expected a boolean but was NUMBER`，true/false 正常。
- **影响**：有消息的聊天列表和搜索结果都可能整批失效。仅检查 HTTP 状态及服务器测试无法发现。
- **修复/验收**：统一 API 布尔类型或增加明确兼容转换；将真实响应结构加入跨端契约测试；验证未点赞、已点赞、空列表及混合消息。
- **证据**：`chat-contract.log`、`ChatContractProbe.java`、真机 `live-device.log`。

### A02 · P1 · 旧会话消息被写入新情侣关系

- **隔离实测**：旧关系 5 已解除，当前关系为 6；请求仍携带 `relationshipId=5`，服务端返回 201，实际记录却存入关系 6。
- **根因**：[chat.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/chat.js:39) 解构了请求 relationshipId，但插入时直接采用查询出的当前关系，没有比较二者。
- **影响**：另一设备解绑/重绑后，旧页面或待上传消息可能投递给新伴侣。客户端本地关系检查无法替代服务端会话边界检查。
- **修复/验收**：发送时校验请求所属关系与当前有效关系完全一致；过期会话返回明确冲突/无权操作；离线队列按原关系保留并阻止跨关系重试。
- **证据**：`chat-route-probe.js`、`chat-route-probe.log`。

### A03 · P1 · Access/refresh token 类型混用，改密和注销没有撤销机制

- **代码链**：[jwt.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/utils/jwt.js:11) 使用同一密钥和同类 payload 生成两类 token；[auth.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/auth.js:115) refresh 只调用通用 verifyToken；[middleware/auth.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/middleware/auth.js:15) 只验证签名/过期时间，不验证用户存在、状态或会话是否撤销。
- **隔离实测**：删除测试账号后，旧 refresh token 仍能换取新 access token；新 token 仍通过业务鉴权。此结果证明会话没有失效，不表示已经读到了被删除的数据。
- **影响**：短期 access token 可以被当成 refresh 使用，轮流续签；密码修改、账号删除等操作无法可靠终止已签发会话。保险箱等敏感接口也依赖同一鉴权机制。
- **修复/验收**：分离 token 用途并验证用途；引入会话/版本或撤销机制；refresh 校验有效用户及会话。覆盖 access 不能刷新、refresh 不能直接访问业务、改密和注销后旧 token 均失效。

### A04 · P1 · AI 限流误作用于普通业务接口

- **隔离实测**：相同测试来源访问 `/api/me/overview` 或 `/api/assets`，第 16 次请求返回 429；`/api/ai-chat/extractions` 因同一请求计数两次，第 8 次即返回 429。
- **根因**：[index.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/index.js:116) 把 `aiLimiter` 挂到整个 `/api`，放在 assets、push、notifications、ai-chat、period、me、password-accounts 之前；未匹配 imageGen 的请求仍先消耗限流额度。[rateLimit.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/middleware/rateLimit.js:34) 的额度为每分钟 15 次。
- **影响**：正常切换资产/个人中心等页面即可消耗 AI 配额，出现与操作无关的“AI 请求过于频繁”；聊天提取接口重复计数。
- 反向遗漏：实际调用外部生图服务的 [inventory.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/inventory.js:717) 在 `/api/inventory` 提前挂载，没有经过这个 AI limiter。全局普通限流仍存在，但不能替代昂贵任务的单独额度。
- **修复/验收**：只在实际 AI 生成/分析路由挂限流，避免重复挂载；普通只读路由与昂贵任务分别验收。

### A05 · P1 · 图片识别接口可访问服务端内网地址

- **隔离实测**：`/api/beads/recognize-colors?mode=algorithm` 的 imageUrl 指向临时 localhost 图片服务；该服务收到请求，识别 API 返回 200。
- **根因**：[beads.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/beads.js:861) 接受客户端 URL，[下载函数](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/beads.js:1071) 未限制目标地址范围，重定向又直接递归跟随。
- **影响**：持有普通账号即可驱动服务端请求内网/本机 HTTP 资源；是否可进一步读取某项敏感服务取决于部署网络，本轮没有探测生产内网。
- **修复/验收**：优先只允许系统上传资源；外链场景校验协议、域名、DNS 解析地址及每次重定向目的地，并限制跳转次数。现有单次下载大小限制不能解决 SSRF。

### A06 · P1 · 声明支持 Android 8，却调用 Android 13 才提供的方法

- **静态 + Lint 确认**：[AuthApiClient.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/api/AuthApiClient.java:952) 使用 `URLEncoder.encode(String, Charset)`；同类调用还在 814、993、1008、1244 行。
- [build.gradle.kts](/Users/chengzi/Code/GitHub/coupleCredit/app/build.gradle.kts:24) minSdk=26，未启用 core library desugaring。Java sourceCompatibility=11 并不等于旧 Android 运行时具备该方法。
- **影响**：API 26–32 设备上，进入热量历史或执行相关食材/聊天搜索存在 `NoSuchMethodError` 崩溃路径。本轮手机是 API 36，因此未在旧手机实测。
- **修复/验收**：改用兼容的字符串编码名重载，或正确配置 desugaring；至少在 API 26/32 与 36 各跑关键入口。

### A07 · P1 · 第 012 号迁移无法在 MySQL 8.0.33 执行

- **隔离实测**：新建库、执行初始化及 002–011 后，012 在第 11 行开始的 INSERT…SELECT 失败：`ERROR 1052 (23000): Column 'bead_inventory.quantity' in field list is ambiguous`。
- **根因**：[012_normalize_bead_color_codes.sql](/Users/chengzi/Code/GitHub/coupleCredit/server/migrations/012_normalize_bead_color_codes.sql:11) 同表 INSERT…SELECT 与 ON DUPLICATE KEY UPDATE 引用产生歧义；后面的图纸颜色合并采用类似写法，也须一起验证。
- **影响**：新环境初始化或旧环境升级无法可靠顺序完成；前面语句可能已提交，不能把迁移当作原子步骤重试。
- **修复/验收**：明确源/目标别名或拆分临时数据；空库、含旧色号、同时含旧新色号、失败后重试四种情况都在真实 MySQL 上执行。

### A08 · P1 · 当前客户端与线上个人概览接口不兼容，静默显示旧数字

- **真机 + 线上只读实测**：同一月份首页有收入，个人概览收入仍为 0；退出子页面回来，概览重新请求 200 后数字仍不更新。
- **已核对响应结构**：线上 `/api/me/overview` 返回 `bills/todos/inventory` 等旧字段；当前 [OverviewData](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/api/AuthApiModels.java:1425) 只声明 `billSummary/todoSummary/inventorySummary`。本地 [me.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/me.js:297) 已是新结构，线上与本地契约不同。
- [MyFragment.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/MyFragment.java:535) 仅请求失败才回退；200 但缺少数据段时 [applyOverviewData](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/MyFragment.java:581) 跳过赋值，先前加载的缓存继续展示。
- **影响**：页面把旧收支/待办数字表现为当前统计；新安装没有缓存时还可能为空。
- **修复/验收**：客户端兼容旧结构或对缺失必要数据段执行明确回退，并制定前后端发布顺序/版本检查。验收不能只请求 healthz；必须比较响应字段和页面数字。本轮未部署服务端。

### A09 · P1 · 编辑小钱包账单不调整余额，随后删除会制造额外余额

- **隔离 API + MySQL 实测**：计划初始 100，新增支出 10 后余额 90；PUT 改支出为 20 返回 200，但余额仍是 90；DELETE 后余额变成 **110**，账单已不存在。正确结果应分别为 80、100。
- [bills.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/bills.js:349) 编辑仅更新 bills，未按照旧/新金额及收支方向同步 shared_plans；删除却根据已修改后的账单退款。
- **验收**：在同一事务中锁定账单与计划，按金额和方向差额更新余额；覆盖增额、减额、收支方向切换、余额不足和编辑后删除，并清理相关缓存。

### A10 · P1 · 并发删除同一账单会重复退款

- **隔离 API + MySQL 实测，5/5 次复现**：初始 100、支出后 90；两个 DELETE 并行均返回 200，账单剩余 0 条，余额变成 **110**。
- [bills.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/bills.js:313) 在事务外读账单；事务内 DELETE 未检查 affectedRows，第二次实际删了 0 行仍执行退款。
- **验收**：使读取、删除、退款形成同一个并发安全操作；第二次删除不产生财务变化。双设备、请求超时重试与并发更新/删除都要核对实际余额。

### A11 · P1 · 同一 AI 提取结果并发确认会重复入账

- **主线程隔离实测，3/3 次复现**：对同一个 pending 提取结果并发发送两次 confirm，两个请求都 200，每次生成两条账单。
- [aiChat.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/aiChat.js:166) 普通 SELECT 查 pending，随后创建业务数据，最后才 UPDATE confirmed；没有事务锁、原子状态领取或业务唯一约束。客户端禁用按钮不能防止另一设备同时确认。
- **验收**：提取状态和业务写入原子化；一个 extraction 只能产生一份结果，重复确认返回同一个 targetId。覆盖事务失败回滚、超时重试和双方同时确认。
- **证据**：`extra-route-probe.js`、`extra-route-probe.log`；未调用外部 AI，使用隔离库内构造的提取结果。

### A12 · P1 · 菜谱保存失败后仍部分写入，编辑失败会丢失原食材

- **隔离实测**：食材名超过数据库字段容量时，新建返回 500 但菜谱主记录已经存在；对已有正常菜谱提交同类异常编辑，返回 500，但标题已经改变，原食材从 1 条变为 **0 条**。
- [recipes.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/recipes.js:366) 先写主表再写食材；[编辑路径](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/recipes.js:393) 先更新主表、删除全部食材再重建，没有事务。字段验证也没有在写入前阻止这个输入。
- **验收**：完整校验主表/食材输入，主表、食材、热量更新统一事务；任一步失败，原菜谱内容必须完全保留。缓存失效放在提交成功后。
- **证据**：`extra-route-probe.log`，仅隔离测试账号。

## 聊天、缓存与异常恢复

### B01 · P2 · 消息重试缺少幂等键，可能重复发送

- **隔离实测**：相同消息 POST 两次，数据库产生两条记录。
- [ChatRepository.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/ChatRepository.java:202) 会重试未同步消息；首次请求服务端已落库但响应丢失时，本地仍没有 cloudMessageId。[chat.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/chat.js:46) 每次直接 INSERT，没有客户端消息 ID/唯一约束。
- 同一页面初始化还能发起多个同步入口；单线程本地队列不等于异步 HTTP 发送互斥。
- **验收**：提交成功但响应超时、同步过程中再次刷新、断网恢复三种场景，同一客户端消息只能产生一条云端记录。

### B02 · P2 · 删除先报成功，离线或服务器拒绝后消息重新出现

- **静态确认**：[ChatRepository.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/ChatRepository.java:129) 先删本地，云端失败只写日志，最后仍调用 onSuccess；离线时根本不排队记录删除操作。
- 下次同步以服务器记录覆盖缓存，删除的消息再次出现。删除别人的消息时，服务器的所有权检查会拒绝，但本地仍已移除。
- **验收**：离线删除、403、500、超时分别验证；采用删除待同步状态/补偿队列，或服务端成功后再确认删除。

### B03 · P2 · 历史消息加载一直请求最新 200 条

- **静态确认**：[CloudChatRepository.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/CloudChatRepository.java:151) 固定 `limit=200,before=null`；[loadOlderMessagesFromCloud](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/ChatRepository.java:387) 仍调用同一个方法。
- 同时 [updateLocalCache](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/ChatRepository.java:534) 清理所有已同步缓存，再只写这 200 条。因此上滑无法取得更老消息，已缓存的更早历史也会被替换掉。云端记录并未因此删除。
- **验收**：构造超过 400 条消息，按服务端时间+稳定 ID 游标分页；刷新不清除已加载历史。

### B04 · P2 · 云端点赞状态落本地时丢失

- **静态确认**：[CloudChatRepository.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/CloudChatRepository.java:260) 正确设置了 message.isLiked，但 [convertMessageToEntity](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/repository/ChatRepository.java:606) 没有复制 isLiked；回读时又采用 entity 的默认 false。
- **影响**：即使修复 A01，已点赞消息经过全量刷新仍会变回未点赞显示。这是独立的数据映射遗漏。
- **验收**：云端 liked=true → Room → 页面 → 重启，状态保持一致。

### B05 · P2 · 刷新令牌的临时网络失败会清除登录状态

- **静态确认**：[AuthApiClient.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/api/AuthApiClient.java:1741) 在 refresh 返回 false 时直接 clearUserInfo；[refreshAccessTokenSync](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/api/AuthApiClient.java:1853) 对超时、连接失败、5xx 与明确无效凭据一概返回 false。
- **影响**：access 自然过期恰逢短暂断网/服务异常时用户被退出。多个并行刷新也没有统一协调，失败回调可能清除另一个请求刚刷新的状态。
- **验收**：明确区分凭据失效与临时网络错误，合并并发刷新，并绑定发起时的账号/会话代次。真机正常刷新已通过，异常路径本轮未扰动真实账号。

### B06 · P2 · 图片上传绕过统一 token 刷新

- **静态确认**：[AuthApiClient.uploadImage](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/api/AuthApiClient.java:680) 自建 HttpURLConnection，401 直接报告“上传失败”；不走 doRequest 的 refresh/retry。
- **影响**：应用停留超过 access 有效期后，若下一项操作是头像/物资图片上传，会失败；其他普通接口却能自动恢复。
- **验收**：过期 access + 有效 refresh，直接上传仍能成功；重试时安全重建输入流，避免重复消耗不可重放流。

### B07 · P2 · 页面缓存与轮询恢复不完整

- [MainActivity.showFragment](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/activity/MainActivity.java:261) 用 hide/show 保留 Fragment。个人概览仅在 [MyFragment.onResume](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/MyFragment.java:464) 刷新；切回“我”不会触发该生命周期，Main 的刷新分派也没有覆盖 My。这使 A08 修好之后仍存在页面内返回时统计陈旧的问题。
- [PollingManager.stop](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/utils/PollingManager.java:47) 清空 currentRefresh；Main 再次 onResume 只 start，未重新设置当前页动作。应用切后台或打开子 Activity 返回后，轮询可能停止，直到再切 tab。
- **验收**：当前页面 → Home → 回应用；当前页面 → 子 Activity → 返回；改变账单后切回个人概览。每条路径都需要验证重新获取并正确显示数据。

### B08 · P2 · 服务端资产筛选污染“全部”缓存

- **隔离实测**：创建在用和已处置资产各一条；先 GET `?status=disposed` 得到 1 条，再 GET 无筛选“全部”仍只有这 1 条，正确应为 2 条。
- [assets.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/assets.js:59) 无筛选读 Keys.assets(userId)，但 [90 行](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/assets.js:90) 把有筛选结果也写入这个键。
- **验收**：仅缓存无筛选全集，或把规范化筛选条件纳入缓存键；按不同请求顺序验证结果，不能只测一次筛选查询。

### B09 · P2 · 收支类型未限制为 0/1，不同统计路径解释不一致

- **隔离实测**：POST bills 的 incomeType=2 被接受，返回 201。
- [bills.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/bills.js:162) 只验证整数；有计划时 0 扣款、所有非 0 都加款。[me.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/me.js:78) 统计只认 1 为收入、0 为支出，其余金额两边都不计。
- **验收**：普通创建、编辑、AI 确认均共享严格的收支枚举和金额校验；非法枚举在任何写入前返回 400。检查历史库是否已有此类行，不擅自自动改值。

### B10 · P2 · 物资新增在事务提交前返回成功

- **静态确认**：[inventory.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/inventory.js:331) 的事务回调内在 395/442 行调用 res.json；[transactions.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/utils/transactions.js:6) 要等回调结束才 commit。
- **影响**：若提交失败，客户端已经收到成功，服务端不能再可靠改为错误响应；提前清缓存也允许其他请求在提交前重新缓存旧数据。本轮没有人为中断真实事务或注入生产提交故障。
- **验收**：回调只返回结果，提交成功后失效缓存并发送响应；隔离测试注入 commit 失败，客户端必须得到失败且数据不落库。

### B11 · P2 · 删除推送 token 没有校验归属

- **隔离实测**：为账号 A 插入测试 push token，账号 B 请求 DELETE `/api/push/token`，返回 200，A 的 token 行被删除。
- [push.js](/Users/chengzi/Code/GitHub/coupleCredit/server/src/routes/push.js:35) 删除条件只使用 token/channel，不使用 req.userId。
- **边界**：调用者须已登录并知道对方 token；没有证明可枚举真实 token，也没有给真实设备发推送或注销注册信息。
- **验收**：删除条件包含当前用户；跨账号删除应拒绝或不改变任何记录；完整验证换账号时注册/注销与通知接收关系。

## UI 与交互

### C01 · P2 · Android 16 系统栏适配不一致

- **真机确认**：热量页标题、返回和“目标”进入状态栏区域，与系统图标重叠；资产和经期页状态栏采用白图标叠浅色背景，可读性明显不足。
- [CalorieActivity](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/activity/CalorieActivity.java:52) 及 [activity_calorie.xml](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/res/layout/activity_calorie.xml:1) 没有与 Main 相同的系统栏 inset 处理；targetSdk 为 36。
- **验收**：统一每个独立 Activity 的系统栏 inset、图标亮暗和键盘 inset；在当前真机及大字体下检查实际画面，不能只靠 XML 编译。

### C02 · P2 · 报表默认标题错误、结余初始未计算

- **真机确认**：默认支出 tab 显示“收入趋势”，图中却是支出总额和支出图例；右上只显示“结余：”。
- [item_report_chart.xml](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/res/layout/item_report_chart.xml:26) 默认写死收入趋势；[ReportFragment](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/ReportFragment.java:141) 创建 ViewHolder 后未按当前类型重绑标题；标题只在 tab 回调且引用非空时更新。
- 结余更新在 [refreshChartData](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/ReportFragment.java:504) 路径，首屏图表的独立加载和日期切换路径没有统一调用它。
- **验收**：首次进入支出、切收入再返回、修改月份、切筛选、刷新、ViewHolder 重建，标题/图例/总额/结余始终一致。

### C03 · P2 · 库存三列布局使时间和长名称失去辨识度

- **真机确认**：最近消耗/更新时间普遍只剩 `2026-...`；长物资名被截断；两个浮动按钮覆盖滚动中段右列内容。
- [InventoryFragment](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java:320) 固定三列；[item_inventory.xml](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/res/layout/item_inventory.xml:37) 名称及时间均受窄列单行省略限制。
- **边界**：本轮滑到底后最后两张卡片操作可完整看到，不能声称所有底部按钮不可点击；已证明的是截断与滚动中段遮挡。
- **验收**：依据可用宽度设计列数/信息密度；时间使用可辨识的短日期或相对时间；FAB 周围保留实际可用空间，并验证三列满行、长单位、长名称和大字体。

### C04 · P2 · Android 16 返回手势绕过“出去吃”的自定义返回逻辑

- **真机确认**：菜谱 → 出去吃 → 屏幕左边缘返回，直接回到 Launcher。
- [MainActivity.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/activity/MainActivity.java:472) 仍重写 onBackPressed 来返回上一个 tab；部分 Lint 同时报 `GestureBackNavigation`。
- **验收**：迁移到 OnBackPressedDispatcher，并覆盖手势、系统返回键、页面内返回以及 Fragment back stack；实际回到菜谱，而不是退出应用。

### C05 · P2 · 编辑账单清空金额后确认存在直接崩溃路径

- **静态确认**：[ClassicModelFragment.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/ClassicModelFragment.java:265) 点击确认直接 Double.parseDouble 输入，未检查空串、单独小数点或捕获 NumberFormatException；运行在主线程。
- **验收**：空金额、`.`、超大值等输入只显示明确提示，保持弹窗可编辑；本轮未打开真实账单提交验证。

### C06 · P2 · 物资 AI 生图成功后，保存把远程图片当成本地 URI 压缩

- **静态调用链确认**：[InventoryFragment.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java:929) 将生成的服务器 URL 写入 pendingImageUrl 并设置 imageChanged；[保存分支](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java:1075) 统一进入 uploadAndSave。
- [uploadAndSave](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java:1094) 把远程 URL 传给 [ImageCompressor](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/utils/ImageCompressor.java:14) 的 ContentResolver.openInputStream；它不能作为 HTTP 下载器，失败返回 null，物资保存被“图片压缩失败”中止。
- **验收**：已生成的服务端 URL 直接作为 imageUrl 保存；本地 content/file URI 才上传。覆盖 URL 与 base64 落盘生成结果；本轮未产生外部 AI 费用。

### C07 · P2 · 物资从非空刷新为空后，页面仍保留旧卡片

- **静态确认**：[InventoryViewModel.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/viewmodel/InventoryViewModel.java:119) 正确清空数据并发布版本；但 [InventoryFragment.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java:209) 只有 hasData=true 才恢复列表。hasData 等价于列表非空，因此空结果不会更新适配器。
- **验收**：另一设备删除最后一项后刷新，卡片、数量和最近动态都清零并显示空态；空列表是正常数据状态，不能与“尚未加载”混用。

### C08 · P2 · 资产页快速切筛选会只改变选中态，不刷新内容

- **静态确认**：[AssetsActivity.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/activity/AssetsActivity.java:209) 点击先改变分类和选中外观，再 loadAssets；[306 行](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/activity/AssetsActivity.java:306) 的 2 秒节流直接 return，没有保留最后一次筛选或安排重试。
- **验收**：连续快速切换至少三个分类，最终列表必须对应最终选择；旧请求迟到也不能覆盖新筛选结果。此问题独立于 B08 的服务端缓存错误。

### C09 · P2 · 已设置自定义服务器后，“测试连接”按钮失效

- **静态确认**：[UserSettingsActivity.java](/Users/chengzi/Code/GitHub/coupleCredit/app/src/main/java/com/example/couplecredit/activity/UserSettingsActivity.java:661) 先注册测试回调，随后 isCustom 分支在 692 行用 setNeutralButton("测试连接", null) 覆盖它；后续没有补回监听。
- **验收**：默认地址和已有自定义地址两种状态，按钮都实际发起测试并呈现结果；本轮未修改真实账号的服务器设置。

## 工程质量与验证缺口

- 项目级 [gradle.properties](/Users/chengzi/Code/GitHub/coupleCredit/gradle.properties:1) 固定本机代理 127.0.0.1:7890；本机实际可用端口是 7897，导致首次 JUnit/Hamcrest 下载失败。本轮只用命令行临时覆盖；建议机器配置从项目共享配置移出。
- 104 项服务端测试通过，并未覆盖真实 Android/Gson 契约、全部 SQL 顺序迁移、关系切换发送及异常同步。不能把绿色测试等同于功能全面正常。
- 已跟踪的 [launchd 配置](/Users/chengzi/Code/GitHub/coupleCredit/server/launchd/com.couplecredit.api.plist:31) 含 DB_PASSWORD/INVITE_CODE 字面值，且与当前本地 server/.env 对应值相同；[daily-backup.sh](/Users/chengzi/Code/GitHub/coupleCredit/server/scripts/daily-backup.sh:12) 也有密码字面量。配置值不应保存在受版本控制文件中，备份不宜用命令行 `-p...` 传密码。未输出任何值、未用这些值登录生产，也不能仅凭看似默认密码就断言生产已泄露；是否仍在生产使用及历史暴露范围需单独核查。
- Android 4 个失败是针对库存布局结构的源码断言，须根据明确的现行 UI 规格修订测试或布局，不能简单删断言“变绿”。
- 部分 Lint 的 42 errors 中：35 项 `UseAppTint`、5 项 `NewApi`、1 项 `GestureBackNavigation`、1 项摄像头 `uses-feature` 声明问题。1388 条 warning 未逐条提升为人工确认缺陷；报告中的运行风险经过单独核对。
- 本轮没有验证：旧 Android 真机、双设备真实推送到达、生产内网/防火墙部署、AI 外部服务计费调用、生产并发压力、真实账户增删改、灾备恢复全过程。敏感和写操作只在隔离实例中验证。

## 建议处理顺序

1. 优先修余额更新/退款并发、AI 确认幂等、菜谱事务，补数据不变量测试；同时阻断会话投递越界、token 混用、SSRF 和错误限流。
2. 修复聊天和个人概览跨端契约、012 迁移及旧 Android API 兼容；真实 MySQL 从零初始化和升级必须完整成功。
3. 修复聊天同步幂等/删除补偿/分页/点赞映射、资产缓存、事务响应时序及页面恢复。
4. 修复输入崩溃、AI 图片保存、空态、筛选、系统栏、返回导航、报表初始化和库存信息密度；再对当前真机与旧 Android 环境回归。
