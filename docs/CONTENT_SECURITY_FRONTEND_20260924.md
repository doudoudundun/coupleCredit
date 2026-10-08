# 内容安全接口前端接入设计（2026-09-24）

## 背景

后端「鉴权加固 + 安全防护」一批改动新增/变更了若干 App 可用接口，本文记录 Android 前端（app/）的接入设计。范围判断依据：接口是否面向本 App 客户端、且服务端有真实数据或行为支撑。

## 一、接入的接口与对应功能

| 接口 | 前端功能 | 涉及文件 |
|---|---|---|
| `POST /api/security/check-text`（scene=1 资料） | 修改昵称时内容安全审核：输入防抖预检 + 提交终检，未通过内联提示 | `AuthApiClient`、`UserSettingsActivity` |
| `GET /api/security/avatar-status` | 「我的」页与设置页展示头像审核状态提示条（审核中/未通过） | `AuthApiClient`、`MyFragment`、`UserSettingsActivity`、两个布局 |
| `PUT /api/auth/avatar`（变更：响应含 `avatarStatus`） | 上传头像后即时反馈：pending → 「已提交审核，通过前对方暂时看不到」 | `AvatarUploadApi`、`MyFragment`、`UserSettingsActivity` |
| `DELETE /api/push/token` | 退出登录 / 注销账号时清理本机推送 token | `NotificationHelper`、`UserSettingsActivity` |

## 二、交互设计要点

### 1. 昵称审核（check-text）

- 服务端口径：`check-text` 恒回 200 + `{ pass, suggest, degraded, message }`，degraded=true 表示微信侧无结论、已放行。
- 输入时 600ms 防抖预检，结果内联展示在输入框下方（绿=合规 / 红=原因），空文本与超长（>20）不发起请求。
- 点「确定」后终检：未通过 → 对话框不关闭，内联红字提示；通过或接口故障（onError）→ 放行提交，与服务端 fail-open 口径一致。
- 用请求序号（checkSeq）丢弃过期响应，防止慢响应覆盖新输入的结论。
- 注册页不接入：`/api/security/*` 需登录态，注册时尚无 JWT。

### 2. 头像审核状态

- 服务端口径：头像上传后 `avatar_status='pending'`（微信异步审核，回调落在 `/api/wechat/callback`），审核期间**对方看不到**（couple-info / overview 门控）；`avatar-status` 的 `shouldNotify` 仅在 pending/rejected 时为 true。
- 「我的」页头卡片与设置页用户信息下方各加一条 `tv_avatar_status` 提示：
  - pending → 「头像审核中，审核通过前对方暂时看不到」
  - rejected → 「头像审核未通过，请更换头像」
  - approved / 未登录 / 查询失败 → 隐藏（失败静默，不打扰）。
- 上传成功回调新增 `avatarStatus` 参数：pending 时 Toast 与提示条即时生效，无需等轮询。

### 3. 推送 token 清理

- `registerPushToken` 落库时同步把 token/channel 记入本地 `push_tokens` prefs。
- 新增 `NotificationHelper.clearRegisteredToken(context)`：读本地 token + 当前 accessToken（同步读取），后台线程 `DELETE /api/push/token`，成功后清除本地记录。
- 调用时机：`performSignOut`（退出登录）与 `performLogout`（注销账号）成功后、`UserInfoManager.clearUserInfo` **之前**（接口需鉴权）。失败不阻断退出流程。

## 三、明确不接入的部分

| 接口 | 不接入原因 |
|---|---|
| `POST /api/auth/wechat-login` / `wechat-bind` / `wechat-register` | 服务端走 `jscode2session`（小程序 wx.login code 换 openid），本 App 无微信 OpenSDK，属小程序客户端功能；App 端接入需换 `sns/oauth2` 流程 + 引入 SDK，另立项目。 |
| `GET /api/notifications` / `PUT /:id/read` | 服务端无任何写入 `notifications` 表的代码（全库无 INSERT），页面必然恒空；待服务端有生产者（如 todo 提醒、伴侣 nudged）后再建通知中心。 |
| `GET /api/beads/inventory/low-stock`、`/summary` | 列表接口 `/api/beads/inventory` 已全量返回等价数据，现有 UI 已覆盖。 |

## 四、验证

- `./gradlew :app:compileDebugJavaWithJavac` 编译通过。
- 真机/模拟器场景：改昵称输入敏感词 → 内联红字且无法提交；上传头像 → Toast「已提交审核」+ 提示条；退出登录后服务端 `push_tokens` 对应行被删除。
