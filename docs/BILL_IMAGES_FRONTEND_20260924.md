# 账单图片（照片/小票）前端接入设计（2026-09-24）

## 背景

后端 bills 模块新增账单图片持久化能力（`server/src/routes/bills.js`）：每条账单可带 `photos`（照片）与 `receipts`（小票凭证）各最多 4 张，图片经 `POST /api/upload/image` 上传后存站内相对路径 `/uploads/...`。前端 `AddBillFragment` 类注释里「7. 照片选择」一直是空坑，本次补齐。

## 服务端契约（已由 scripts/verify-bill-images.js 25/25 实测确认）

- `POST /api/bills` / `PUT /api/bills/:id` 接受 `photos`/`receipts`：字符串数组，元素必须是 `/uploads/...` 或 `https://`，最多 4 张；
- **PUT 语义**：不传字段 = 保留原值；`[]` = 明确清空；非空数组 = 整体替换；
- `GET /api/bills` 每条账单回 `photos`/`receipts` 数组（历史无图账单为 `[]`）。

## 前端设计

### 数据链路
- `AuthApiModels.BillData` 增加 `photos`/`receipts`；`CreateBillRequest`/`UpdateBillRequest` 增加同名字段。
  - Gson 默认省略 null → `UpdateBillRequest` 中 null 即「不传=保留」，空数组即「清空」，与服务端 `hasOwnProperty` 语义天然对齐；
- `BillBean` 增加 `photos`/`receipts` + `getImageCount()`；`ClassicViewModel` 映射时透传；
- `AuthApiClient.createBill/updateBill`、`BillUtils.insertBill/updateBill` 增加带图重载，旧签名全部委托新签名（null）保持兼容——未改造的调用点（如 CategoriesBillViewActivity 的编辑）不传图片字段，服务端保留原图，无数据丢失风险；
- `BillUtils.absoluteImageUrl(context, url)`：`/uploads/...` 拼 `ApiConfigManager` 当前 baseUrl，http(s) 原样返回。

### UI：BillImageStripView（app/view/，记账页与账单详情弹窗共用）
- **编辑态**：「照片」「小票」两个添加按钮（灰 chip + ic_add_photo），缩略图 48dp 横条；缩略图带「照/票」角标区分类型，右上「×」删除；每类满 4 张置灰按钮；
- **查看态**：隐藏按钮，点缩略图全屏预览；无图且查看态时整条隐藏不留空白；
- 选图：宿主 Fragment 用 `ActivityResultLauncher`（StartActivityForResult + RequestPermission，READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE 按 API 版本）发起系统选图，把 Uri 交回 `onImagePicked(uri, type)`；
- 上传：`ImageCompressor.compress(uri, 1080, 85)` → `AuthApiClient.uploadImage`（multipart `image`，服务端 sharp 校验真实格式、5MB 上限），期间显示上传中占位；失败仅 Toast 不阻断记账；
- 接入点：
  1. `AddBillFragment`：备注行下方新增条；保存时若仍在上传则提示「请稍候再保存」，随账单一起提交；「再记/清空」同时清图；
  2. `ClassicModelFragment` 账单详情弹窗（`dialog_layout.xml`）：初始查看态展示，点「编辑」切编辑态，取消还原原图，确认后整体替换提交并同步本地 BillBean。

### 列表展示
- `item_bill.xml` 增加 `ll_image_badge`（ic_image + 数量），`BillAdapter` 按 `bill.getImageCount()` 显隐。

## 测试

- `ApiContractCompatibilityTest` 新增：BillData 带图/历史无图解析；UpdateBillRequest 的 Gson 序列化语义（null 字段省略、空数组 → `"photos":[]`）；CreateBillRequest 带图序列化。
- 全量 `testDebugUnitTest` 通过；服务端 `verify-bill-images.js` 本地 25/0。

## 遗留

- `CategoriesBillViewActivity` 的编辑弹窗暂未加图片编辑条（更新时不传字段，原图保留，安全）；后续可复用 BillImageStripView 补齐。
- 本地验证产生的测试账号 `imgtest7l4ylo`、`probeimg7854` 留在本地 dev 库（脚本设计为「账号需手工清」）。
