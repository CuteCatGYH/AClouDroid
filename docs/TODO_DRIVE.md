# iCloud Drive 开发路线图 (TODO_DRIVE.md)

> **定位**：本文档是 [TODO.md](TODO.md) 中 Phase 5「iCloud Drive」的独立展开版。
> Photos 侧已基本完成（见 [TODO.md](TODO.md) Phase 0~4），Drive 是下一个功能域。
>
> **核心结论**：**iCloud Drive 与 iCloud Photos 是两套完全不同的协议栈**，
> Cookies / Header / 会话握手可以复用，**业务模型与接口一律不能照搬**。
> 开工前请务必读完本文第一节与 [AGENTS.md](AGENTS.md) 中的相关章节。

## 📚 相关文档

| 文档 | 与本篇的关系 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 协作规范、编译调试、会话/URL/错误判定红线（**必读**） |
| [TODO.md](TODO.md) | 总路线图，Drive 在其中作为 Phase 5 |
| [PITFALLS.md](PITFALLS.md) | 工程避坑指南（会话、竞态、对账、Compose 布局） |
| [REFERENCE.md](REFERENCE.md) | 开源项目对照表；Drive 字段字典将补充为 §5 |
| [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md) | 已修复问题案例库 |

---

## 0. 为什么 Drive 不能沿用 Photos 的模型

在写任何代码之前，必须先接受这个差异表。Photos 阶段积累的直觉在 Drive 上会**大面积失效**。

| 维度 | Photos（已完成） | Drive（本阶段） |
| --- | --- | --- |
| 后端服务 | CloudKit `pXX-ckdatabasews.icloud.com[.cn]`<br>`/database/1/com.apple.photos.cloud/...` | CloudDocs / `ubd`<br>`pXX-drivews.icloud.com[.cn]` |
| 元数据模型 | `CPLAsset` + `CPLMaster` **双记录**，一一配对 | **文件系统式**：节点层级 + 每文件独立 record |
| 目录结构 | **无目录**，按 `assetDate` 时间线平铺 | **真实目录树**（`/Documents`、iWork 文档包、共享目录） |
| 排序依据 | `assetDate`（拍摄时间） | `name` / `dateModified` / `size`（用户可见列） |
| 增量同步 | `syncToken` + `/records/changes` | **无全局游标接口**（`changes/database` 实测 404），需 D3 重新侦察 |
| 上传协议 | 单文件直传：`/assets/upload` → `/records/modify` | **分片上传**：`/upload/web`（`X-APPLE-JOB-ID`） |
| 下载协议 | `cvws` 直链（`downloadURL` 字段） | `download/by_id` → `data_token` → `ws/download` 直链 |
| 查重依据 | 客户端 SHA-256 + 服务端 MMCS 21 字节指纹 | `etag` / `contentChangeTag` |
| 特殊对象 | Live Photo（图 + 视频双资源） | **iWork 文档包**（Pages/Numbers/Keynote 是 zip 目录，显示为一个文件） |

> [!CAUTION]
> **D0 实测推翻了本文最初的模型假设（详见 [REFERENCE.md](REFERENCE.md) §5）。**
> 修正后的真相：
>
> | 事实 | 原假设 | 实测 |
> | --- | --- | --- |
> | 节点类型字段 | `bundleType` | **不存在该字段**，用的是 `type` |
> | 类型取值 | `FOLDER` / `FILE` / `APP_BUNDLE` | `FOLDER` / `FILE` / **`APP_LIBRARY`** |
> | `APP_LIBRARY` 是什么 | iWork 文档包 | **App 文档容器**（Keynote/GarageBand/快捷指令…） |
> | 结构增量接口 | `changes/database` | **不存在**（404 `WSModuleNotFoundException`）|
>
> 因此：schema 里**不要**加 `bundleType`，直接存 `type`；
> D3 的增量方案**必须重做侦察**，不能照本节原计划写。

---

## 1. 开发阶段总览

按「风险早暴露 → 依赖少 → 每阶段可独立验证」排序。

```
D0 协议侦察与探针（必须最先）
 └→ D1 Go 协议底座：目录列举
      └→ D2 数据层 + 目录浏览 UI   ← 第一个可见里程碑
           └→ D3 增量同步与对账
                └→ D4 下载
                     └→ D5 上传（分片，风险最高）
                          └→ D6 目录操作与冲突处理
                               └→ D7 预览与分享
                                    └→ D8 跨功能收尾（登出清洗 / 设置项）
```

| 阶段 | 内容 | 依赖 | 状态 |
| --- | --- | --- | --- |
| D0 | 协议侦察与探针 | — | ✅ **已完成**（见 [REFERENCE.md](REFERENCE.md) §5） |
| D1 | Go 协议底座：会话枚举 + 目录列举 | D0 | ✅ **已完成** |
| D2 | Kotlin 数据层 + 目录浏览 UI | D1 | ✅ **已完成** |
| D3 | 增量同步与对账 | D2 | ✅ 已完成 |
| D4 | 下载（直链 + 落盘） | D2 | ✅ **已完成** |
| D5 | 上传（单文件直链 + 队列） | D4 | ✅ **已完成**（包含单文件流式上传、队列与 Worker 保活、云上贵州 1GB 上传限制预检及拦截，见 §7） |
| D6 | 目录操作与冲突处理 | D3 | ✅ **已完成**（包含新建文件夹、重命名、云盘层级嵌套移动选择器、移入最近删除、App内网页版垃圾桶弹窗、TopAppBar 状态栏 Padding 适配，见 §8） |
| D7 | 预览与分享 | D4 | ✅ **已完成**（含 Coil+ExoPlayer 媒体预览、FileProvider+ACTION_VIEW 外部查看、iWork 网页版嵌入及 FileProvider+ACTION_SEND 系统分享，见 §9） |
| D8 | 跨功能收尾 | D2~D7 | ✅ **已完成** |

**MVP 范围** = `D0 + D1 + D2 + D4`：能登录 → 能浏览目录树 → 能下载文件。
这是一个可以实机验收的最小闭环，后续阶段都是在这个骨架上加能力。

---

## 2. D0 · 协议侦察与探针 ✅ 已完成

> **结论已归档：[REFERENCE.md](REFERENCE.md) §5。** 探针代码已删除。

### 实测得到的关键结论（详见 REFERENCE §5）

- [x] **`drivews` 端点确实存在**：`https://p203-drivews.icloud.com.cn`，
      无需额外 PCS 握手，从 `/validate` 直接取得
- [x] **目录列举可用**：`POST {drivews}/retrieveItemDetailsInFolders`，
      body 为 JSON **数组**，**无 query 参数**
- [x] **下载令牌可用**：`GET {docws}/ws/com.apple.CloudDocs/download/by_id?document_id=`
      → `data_token.url` 落在 `cvws.icloud-content.com.cn`（与 Photos 同一个 CDN）
- [x] **字段字典已落盘**：41 个真实节点的字段覆盖度统计
- [x] **`Session.DrivewsURL` 已落地**（`account.go`：`handleValidateResponse` + `normalizeDrivewsURL`）

### 被推翻的假设（重要）

- [x] ~~`bundleType` 字段~~ → **不存在**；用 `type`（`FOLDER` / `FILE` / `APP_LIBRARY`）
- [x] ~~`APP_LIBRARY` = iWork 文档包~~ → 它其实是 **App 文档容器**
- [x] ~~`changes/database` 结构增量~~ → `404 WSModuleNotFoundException`，**接口不存在**

### 遗留（不属于 D0）

- [ ] iWork `.pages` 文件在列举中究竟是 `FILE` 还是展示为包 —— 本次样本中无该类型文件，
      D2 前补验一次

### 原任务（已全部执行完毕，保留供回溯）

- [x] 确认 `webservices` 中 Drive 端点的 key 名称 → `drivews`
- [x] 新增临时探针 `go-core/probe_drive.go`（**用完即删**）
- [ ] 新增临时探针 `go-core/probe_drive.go`（**用完即删**）
  - 必须**跑在 App 进程内**（Cookie 在加密存储里，外部脚本拿不到）
  - 必须**避开启动竞争**：`initSession` 之后 `delay(6_000)` 再探
    （见 [PITFALLS §15](PITFALLS.md)）
  - 用 `BuildConfig.DEBUG` 包裹，禁止混入发布包
- [ ] 抓取并落盘真实报文（写进 `cacheDir`，用完删）：
  - `POST /retrieveItemDetailsInFolders`（根目录 + `/Documents`）
  - ~~`POST /changes/database`~~ → 已证不存在，**不要重复尝试**
  - `POST /download/by_id`（拿 `data_token` 与直链形态）
  - `POST /upload/web`（分片流程的第一步应答）
  - 目录里同时放一个 **iWork 文档包**与一个 **普通文件夹**，**逐字段 diff 两者差异**
- [ ] 把结论整理进 [REFERENCE.md](REFERENCE.md) 新增的 **§5「iCloud Drive 实测字段字典」**

### 参照物

- [rclone/backend/iclouddrive/api](https://github.com/rclone/rclone/tree/master/backend/iclouddrive/api)
  —— [REFERENCE.md](REFERENCE.md) §1 已确认它**只实现了 Drive、没实现 Photos**，
  正好反过来用。它能给出完整 endpoint 清单与 `/upload/web` 分片格式。
- 需要在结论里写明：**哪些能直接复用 Photos 侧的 Go 代码、哪些完全缺失**。

### 验收标准

| 项 | 标准 |
| --- | --- |
| Drive 端点 | 在 `/validate` 响应中拿到了可用的 `drivews` URL |
| 目录列举 | 能列出根目录至少 1 层，含文件夹与文件两类 |
| iWork 包识别 | 能明确区分「文件夹」与「文档包」的字段特征 |
| 字段字典 | [REFERENCE.md](REFERENCE.md) §5 已写入，且每条都标注了来源（实测/推测） |
| 探针清理 | `probe_drive.go` 已删除，无残留调用 |

> [!CAUTION]
> **禁止「读代码 + 猜字段」得出结论。** 必须用实测报文。
> 拿到的每个字段都要能回答「这条结论是实测的还是猜的」。

---

## 3. D1 · Go 协议底座：会话枚举 + 目录列举 ✅ 已完成

### 任务

- [x] `Session` 新增 `DrivewsURL` 字段 ✅（D0 顺手已完成）
  - 解析 `webservices["drivews"]`，**回写到 `Session` 自身**
  - 抽出了 `handleValidateResponse()`，避免 `/validate` 与 `/accountLogin` 两条路径重复
  - `normalizeDrivewsURL()` 剥掉 Apple 固定附加的 `:443`
- [x] 新建 `go-core/drive.go` ✅
  - `RetrieveItemDetailsInFolders(parentDrivewsID string) string`
  - URL 拼接：`{DrivewsURL}/retrieveItemDetailsInFolders`（纯 POST，无额外 query）
  - 请求注入 Cookie 与匹配区域 Header (`Session.NewRequest`)
  - 自动处理顶层数组/对象响应反序列化
- [x] 统一节点 JSON 模型（`DriveItem` / `DriveIcon` / `DriveFolderListResult`） ✅
  - 字段对齐：`drivewsid` / `docwsid` / `parentId` / `name` / `type` / `zone` / `size` / `dateModified` / `dateChanged` / `dateCreated` / `etag` / `extension` / `directChildrenCount` / `item_id` / `icons` / `supportedTypes` / `supportedExtensions`
- [x] 错误码处理与单元测试 ✅
  - 复用 `ResultBase`，映射 HTTP 401/404 等状态码为结构化 `errorCode`
  - `go-core/drive_test.go` 模拟 `httptest` 覆盖 200/401/404 及 Session 回写逻辑
  - Kotlin 侧 `DriveParsingTest` 单元测试通过 (232 项单元测试全绿)
  - 实体机 JNI 真机调用联调通过 (Root 41 项孩子列出，未卸载 App)

### 刻意不做

- **不做递归整树拉取**。只暴露「按 parentId 列一层」的能力，递归由调用方控制。
- **不做 Drive 专用的 session 重建逻辑**。复用现有 `ensureGoSession()` 守卫。

### 验收标准

| 项 | 标准 |
| --- | --- |
| 单元测试 | `RetrieveItemDetailsInFolders` 用 `httptest` 模拟，覆盖正常/401/404 |
| `Session` 回写 | 测试断言 `doValidate()` 后 `Session.DrivewsURL` 非空 |
| `go test ./...` | 全绿 |

---

## 4. D2 · Kotlin 数据层 + 目录浏览 UI ✅ 已完成

> **第一个能看见东西的里程碑 —— 已实机验收。**

### 4.0 前置重构（必须在 D2 编码前完成）

- [x] **把 `dataMutationMutex` 从 `PhotoRepository` 提取为中立设施** ✅
  - 新建 `data/lock/DataMutationLock.kt`，由 `AppContainer` 单例持有
  - `PhotoRepository` 改注入（保留 `dataMutationMutex` 别名，侵入面最小）
  - Drive 与 Photos 现在共用同一把锁（AGENTS §12）
- [x] 同理评估 `ensureGoSession()` / `classifyCoreResponse()` / 三层刷新去重
  - 结论：Drive 的 `ensureGoSession` **不能**直接复用 Photos 版本 ——
    后者以 `ckDatabaseURL` 为就绪依据，而 Drive 需要 `drivewsURL`；
    账号可能开了 Photos 却关了 Drive。因此写了一份 Drive 专用版本，
    但**共用同一个 `Icloudcore.initSession` 与同一份加密 Cookie**，不重复实现握手。
  - `classifyCoreResponse` 未复用：Drive 侧只需要一个 `isAuthError()`
    （读结构化 `errorCode`），比引入整套 `CoreErrorKind` 更轻。
  - 三层刷新去重留给 D4（Drive URL 刷新才需要）。

### 4.1 独立 Room 库

- [x] 新建 `feature/drive/data/DriveDatabase.kt`（`icloud_drive.db`，v1） ✅
  - 与 `PhotoDatabase` 完全独立（专库专用）
  - 未启用 `fallbackToDestructiveMigration()`：v2 到来时写真正的 Migration

表设计（草案，D0 探明后可能调整）：

已落地表：

| 表 | 关键列 |
| --- | --- |
| `drive_items` | `drivewsid`(PK) / `docwsid` / `parentDrivewsid` / `name` / `type` / `zone` / `size` / `dateModified` / `dateChanged` / `dateCreated` / `etag` / `extension` / `directChildrenCount` / `itemId` / `iconUrl` / `childrenFetched` / `lastSyncedAt` |

未建的表（故意延后）：

- `drive_sync_state` —— D3 侦察出真实增量机制后再定形状
- `drive_download_queue` —— D4 引入

> [!IMPORTANT]
> **主键是 `drivewsid`，不是 `docwsid`。**
> 实测：每个 `APP_LIBRARY` 的 `docwsid` 都是字面量 `"documents"`，
> 用 `docwsid` 当主键会让 Keynote/Preview/GarageBand 互相覆盖。
> 单测 `DriveItemMapperTest` 里有一条专门盯这个。

> [!CAUTION]
> **`bundleType` 这一列不存在了（D0 实测推翻）。**
> 直接存 `type`：`FOLDER` / `FILE` / `APP_LIBRARY`（详见 [REFERENCE.md](REFERENCE.md) §5.1）。
>
> 三者在 UI 上的建议表现：
>
> | `type` | UI | 行为 |
> | --- | --- | --- |
> | `FOLDER` | 文件夹图标 | 点击下钻 |
> | `FILE` | 按 `extension` 选图标 | 点击预览/下载 |
> | `APP_LIBRARY` | **App 图标（`icons[].url`）+ App 名** | 点击下钻 |
>
> `APP_LIBRARY` 的 `zone` 不是 `com.apple.CloudDocs`，`docwsid` 恒为 `"documents"`。
> 根目录会同时出现「用户文件夹」与「一列 App 容器」，视觉上要区分开。

### 4.2 数据层

- [x] `feature/drive/data/DriveRepository.kt` ✅
  - 所有 Go 调用收敛于此，UI 层不碰 `Icloudcore.*`
  - `refreshFolder()` 过 `ensureGoSession()`，401/421 自动重建并重试一次
  - `isAuthError()` 读结构化 `errorCode`，**不做字符串子串匹配**（AGENTS §7.1）
  - `beginWipe` / `endWipe` / `wipeAllData` 登出守卫齐全
  - 写操作持共享的 `DataMutationLock`
- [x] `feature/drive/data/DriveItemMapper.kt` ✅（纯函数，9 条单测覆盖）
- [x] 懒加载模型：**按 `parentDrivewsid` 查一层**，不做整树 model 化

### 4.3 接入壳层（三步走）

- [x] `AppDestination.Drive` 已存在（无需改动）
- [x] `ui/AppShell.kt` → **替换了原来的 WebView 占位**，接 `DriveEntry`
- [x] `di/AppContainer.kt` → `driveDatabase` + `driveRepository` 两个 `lazy` 字段
- [x] `AuthViewModel.logout()` → Drive 接入四阶段清洗（阻断 → Room → Coil → 会话）

### 4.4 UI

- [x] `feature/drive/ui/` 自包含包（`DriveScreen` + `DriveViewModel` + `DriveEntry`）
- [x] 面包屑（横向可滚 `LazyRow`，末级不可点）+ 文件列表
- [x] 每行：图标 / 名称 / 副标题
  - `APP_LIBRARY` 用远程 App 图标（走项目自己的 `imageLoader`，
    不是 `Coil.imageLoader(context)` 单例 —— 见 AGENTS §6.6 硬规则①）
  - 副标题按字段存在性拼接：`APP_LIBRARY` 没有 `size` / `dateModified`，
    不会留下多余分隔符
- [x] **单层 `LazyColumn`**，无嵌套惰性网格
- [x] **`BackHandler`**：目录内返回上一级，根目录才放行退出

### 验收标准（实机 SM-F9760 已验）

| 项 | 标准 | 结果 |
| --- | --- | --- |
| 浏览 | 根目录 + 至少 3 层下钻 | ✅ root 41 项 → Documents 4 项，继续可下钻 |
| 面包屑 | 点击任一级回跳 | ✅ `iCloud Drive › Documents` 正确渲染，末级不可点 |
| 返回键 | 上一级而非退出应用 | ✅ 日志显示回到 root 重新列举，`topResumedActivity` 始终 MainActivity |
| App 容器 | 与普通文件夹视觉可区分 | ✅ Days Matter / Keynote / Preview / GarageBand / Shadowrocket / Shortcuts 均列出 |
| 排序 | 容器在前，名称不区分大小写 | ✅ |
| 冷启动 | 会话重建 ≤ 1 次 | ✅ 切换目录不重建 |
| 崩溃 | 0 | ✅ 无 `AndroidRuntime` 异常 |
| 单测 | 全绿 | ✅ **243 passed, 0 failed**（新增 9 条 mapper 测试 + 2 条 JSON 测试）|

> [!NOTE]
> **“iWork 包显示为文档图标”这一条改了：** D0 已证实 `APP_LIBRARY` 不是 iWork 文档包，
> 而是 App 文档容器（且应显示为**可下钻的 App 图标行**，不是可预览的文档）。
> iWork `.pages` 在列举中的真实表现仍待验证（本次账号无该文件）。

---

## 5. D3 · 增量同步与对账 ✅ 已完成

> [!IMPORTANT]
> **已实现并实机验证。** 方案：目录级 `etag` 差量列举（非全局游标）。
> 代码：[DriveDeltaPlanner.kt](app/src/main/java/com/getup/iClouDroid/feature/drive/data/DriveDeltaPlanner.kt)
> （纯函数，20 条单测） + `DriveRepository.syncIncremental()`。
>
> 实机日志：`syncIncremental: purged 2 rows under ...::root`
> —— 云端删除的 2 个文件夹在本地被正确清除。

### 5.0 侦察结论 ✅ 已完成（详见 [REFERENCE.md](REFERENCE.md) §5.7）

- [x] **`changes/*` 全线排除**：`changes/zone` 与 `changes/database` 的
      POST/GET/带 dsid/zones body 共 6 种形态，**全部** 404 `WSModuleNotFoundException`
- [x] **`com.apple.CloudDocs` 不是 CloudKit zone**：
      拿**已知能用**的 ck 主机去查得到 401 `AUTHENTICATION_FAILED`，
      同主机对 photos zone 却 200（对照成立）
- [x] **`retrieveItemDetails` 可用**（返回节点元数据、不返回子项），
      但 body 是 `{"items":[...]}` **包裹**形式，与 `InFolders` 的裸数组不同
- [x] **`etag` 稳定**：根目录连续两次列举 etag 均为 `nrp`，35 项 / 701 文件一致

> [!IMPORTANT]
> **采纳方案：目录级 `etag` 差量列举**（非全局游标同步）。
> 每次只重列 `etag` 或 `directChildrenCount` 变了的目录。
> 对几千文件的量级完全够用，且出问题时只需回答“哪个目录的 etag 没变”。

### 第二轮侦察（D3b，已完成，详见 [REFERENCE.md](REFERENCE.md) §5.8）

- [x] **回收站：四种入口全试过，都不是** —— `trash` 类 id 返回 `ID_INVALID`，
      docws 上的 `trash` 模块/处理器均 404。**仍未找到入口**。
- [x] **`etag` 稳定** —— Desktop 19 个子项连续两次列举全部一致；
      `retrieveItemDetails` 单节点查询的 etag 也与列表一致。
- [x] **Drive 上传令牌已打通** —— 完整字段集已确认（见 REFERENCE §5.8C）
- [x] **服务权限已摸清** —— 31 个 active 服务，含 `iworkexportws` / `iworkthumbnailws` / `cksharews`

### 第三轮：全端点普查（已完成，详见 [REFERENCE.md](REFERENCE.md) §5.9）

16 个端点逐个实测，**全部打通**。关键成果：

- [x] **回收站写入口找到了**：`{drivews}/moveItemsToTrash`（POST）
      —— 不在 docws 上，前两轮找错了主机
- [x] **文件夹/改名/移动全可用**：`createFolders` / `renameItems` / `moveItems`
- [x] **`update/documents` 必填 `command`**，空 body 会报 `Unexpected input`
- [x] **复制会真的复制**（不是报错）—— 探针期间误建了一个副本，已清理
- [x] **上传令牌拿到后，`url` 可直接 POST 字节**

> [!IMPORTANT]
> **写接口全部带 `etag`，是乐观并发控制。**
> `renameItems` / `moveItems` / `moveItemsToTrash` 的每个 item 都需传 `etag`，
> 所以本地做任何写操作前**必须先拿到最新 etag**。
> 这与 D2 的 `drive_items.etag` 列正好对应。

### 仍然卡住的点

> [!WARNING]
> **① 读回收站内容的接口仍未知。** 写入口 `moveItemsToTrash` 已找到，
> 但怎么“列出回收站里有什么”仍未探明。
> **不过**：`unified_format=false` 的返回里带 `deleted: bool` 字段，
> 可能是比回收站索引更直接的软删除信号，D3 可优先试这条。
>
> **② `etag` 只证实了“稳定”，没证实“子文件内容变更会 bump 父目录 etag”。**
> 在此之前，**文件夹级 etag 只能用来检测结构变化（增/删/改名）**，
> 不能用来检测内容变化。

### 原计划（已作废，保留供回溯）

- [x] ~~`changes/zone`~~ —— 不存在
- [x] ~~`ckdatabasews` 的 CloudKit 层 delta~~ —— CloudDocs 不是 CK zone
- [x] ~~`syncToken` 持久化~~ —— 没有游标可存

### 其余对账纪律（仍然适用）

> 下面这些与具体增量机制无关，是项目踩过的通用坑，D3 实现时必须遵守。

- [ ] **不得复用 `scan_state`**（AGENTS §12 明令禁止跨功能复用游标表）
- [ ] 「是否走完」必须是**显式返回值**，不能靠函数正常返回
  （[PITFALLS §18.1](PITFALLS.md)：`ScanRunResult(scanned, walkCompleted)`）
- [ ] 对账闸门用**可推导判据**：`seenIdCount >= localCount`
  —— 禁止拍百分比（[PITFALLS §18.3](PITFALLS.md)：50% 阈值拦不住「只剩后半段」）
- [ ] 删除是两段式，**移入「最近删除」不会给 tombstone**
  （[PITFALLS §17](PITFALLS.md)）—— 需要显式拉取最近删除索引，与 Photos 同理
  - **接口位置仍未探明**（见 §5.0 「开工前仍需补验」），实现删除感知前必须查
- [ ] 所有写操作持 `DataMutationLock`，并检查 `isWiping`
- [ ] 接入 `WorkManager`：区分「增量同步」与「全盘对账」两个入口，
  **全盘对账必须经过用户确认**（AGENTS §10.0：主页刷新只做增量）

### 实现细节（已完成）

- [x] `DriveDeltaPlanner`：纯函数，决定 `LIST_UNKNOWN` / `LIST_CHANGED` / `LIST_STALE` / `SKIP`
      - **20 条单测**覆盖每个分支，包括边界（`age` 恰好等于阈值）、负 age、循环父指针
- [x] `DriveRepository.syncIncremental()`：父先子后的遍历 + 只重列变化的目录
      - 整轮持 `DataMutationLock`（跨目录释放会与前台刷新交错）
      - 每轮容器预算 200（不阻塞前台刷新）
- [x] 删除感知：重列后比对本地子项，缺失的连同整个子树一起清除
- [x] **删除不会被写回**（这是原计划最担心的点）：只有全量重列才能产生删除判断，
      而重列一定代表真实响应
- [x] Go 侧 `RetrieveItemDetails` + 5 条单测（含“包裹体 vs 裸数组”回归测试）

### 验收标准（实机 SM-F9760 已验）

| 项 | 标准 | 结果 |
| --- | --- | --- |
| 新增文件 | 网页端新建 → 本地同步可见 | ✅ 机制就位（重列即全量替换）|
| 重命名/移动 | 网页端操作 → 本地层级正确更新 | ✅ 同上 |
| 删除 | 网页端删除 → 本地消失，且不会被写回 | ✅ **已实测**：`purged 2 rows` |
| 断点续扫 | 中途杀进程 → 重启后库完好 | ✅ 每容器独立事务，不会半写 |
| 冷启动 | 不触发无谓的全量扫描 | ✅ etag 未变的目录零写入 |
| 单测 | 全绿 | ✅ **263 passed**（新增 20 + 5）|

### 已知局限（已在代码注释里说明）

> [!WARNING]
> **冷启动时只检查一个目录。** 新建的 `DriveViewModel` 里，缓存树只有根目录，
> 所以首次同步只检查 1 个容器。深层目录需要用户展开一次才会成为候选。
> 这是有意为之的（不做启发式递归拉取），但意味着「用户从未打开过的深层目录」
> 不会自动发现变化。
>
> **`etag` 只覆盖结构变化。** 文件**内容**变化是否 bump 父目录 etag 仍未验证，
> 所以内容变化本次不保证检测到。

---

## 6. D4 · 下载（直链 + 落盘）✅ 已完成

> **实机验证**：5.8 MB HEIC 下载成功，字节数与列表一致。
> 代码：[drive_download.go](go-core/drive_download.go) +
> [DriveDownloader.kt](app/src/main/java/com/getup/iClouDroid/feature/drive/data/DriveDownloader.kt)

### 任务

- [x] `download/by_id` 拿 `data_token` / `thumbnail_token`（Go 侧 `RetrieveDownloadURL`）
- [x] 落盘走 `MediaStore.Downloads`（非媒体文件）
  - **流式**：Go 写 `cacheDir` → Kotlin 拷进 MediaStore 流，全程不用 `[]byte`
- [x] `IS_PENDING` 防半写可见，失败时删行（否则留下隐形零字节条目）
- [x] 文件名净化（防路径穿越）+ MIME 推断，**20 条单测**
- [x] **重复下载生成 `(1)` `(2)` 新文件，永不覆盖**
  - 后缀在扩展名**之前**（`report (1).pdf` 而非 `report.pdf (1)`），保证仍能被正确打开
  - 后缀由 App 自己算，不依赖 MediaStore 的去重行为（后者不是 API 契约）
  - 下载失败不再丢失旧文件（已取消“先删后写”）
- [x] **下载通知与直接调起**（[DriveDownloadNotificationHelper.kt](file:///Users/syh/iClouDroid/app/src/main/java/com/getup/iClouDroid/feature/drive/data/DriveDownloadNotificationHelper.kt)）
  - `icloud_drive_download` 专用渠道（`云盘文件下载`）
  - 采样 `tempFile.length()` 实时刷新通知栏百分比/已下载大小
  - 下载完成弹出通知，点击通过 `Intent.ACTION_VIEW` + `PendingIntent` 调起系统/第三方 App 直接打开本地文件
- [x] **断点续传能力**：保留 `.part` 临时文件，支持 HTTP Range 206 自动追加续传
- [x] **主动取消下载（已解 JNI 阻塞脱节）**：Go 原生 Context Cancellation 彻底切断底层网络流，通知栏与 UI 点击同步毫秒级响应
- [x] **多任务排队与并发管理**：新增 `DriveDownloadQueue` 管理任务 `QUEUED` / `DOWNLOADING` / `SUCCESS` / `FAILED` 状态
- [x] **文件名后缀自动显示**：`DriveItemEntity.displayName` 自动补全 `.ext`，解决 UI 无后缀问题
- [x] **D7 预览前置衔接**：已下载文件本地 Intent 直接调起 + `fetchThumbnailUrl` 接口预留

### 踩到的一个大坑（已修，已加回归测试）

> [!CAUTION]
> **CDN 对带 `Origin` / `Referer` 的请求返回 `503 Bad Gateway`。**
>
> `Session.NewRequest` 会给**每个**请求盖上 `Origin: https://www.icloud.com` —— 这对 API 主机
> 是对的，对 CDN 是致命的。签名 URL 不接受这种来源信息。
>
> 表现：下载 100% 失败且报 503，但 `download/by_id` 本身完全正常，
> 很容易误判为“端点有问题”。
>
> 修法：下载路径用 `http.NewRequest` 而非 `s.NewRequest`，不带 Cookie/Origin/Referer。
> 回归测试 `TestDownloadToFileSendsNoOriginOrReferer` 盯着这条。

### 未采用：三层 URL 刷新去重

> [!NOTE]
> 原计划要复用 Photos 的 `refreshUrlsForMaster` 三层去重，**实际不需要**：
> Drive 的 URL 是每次下载现 mint 的，不缓存、不跨进程复用。
> 加一层缓存反而会引入“URL 过期”这个 Photos 独有的问题。
> 简单地每次重取，才是这里正确的选择。

### 验收标准

| 项 | 标准 |
| --- | --- |
| 小文件 | 下载完成，落盘于 Downloads，内容校验一致 |
| 大文件（>100MB） | 无 OOM，无 JNI 崩溃 |
| 直链过期 | 12 分钟后触发主动换链，不出现 410 才对用户报错 |
| 并发去重 | 同一 docwsid 5 分钟内只发一次 `download/by_id` |

---

## 7. D5 · 上传

> [!IMPORTANT]
> **协议第一步已实测打通（[REFERENCE.md](REFERENCE.md) §5.8C）**，
> 且**不是**分片：`/upload/web` 返回的是 `singleFileUpload` 直链（与 Photos 同构）。
>
> 确认的请求体（`POST {docws}/ws/com.apple.CloudDocs/upload/web`）：
>
> ```json
> {"filesize":N, "filesignature":"<sha256 base64>", "type":"FILE",
>  "filename":"a.txt", "content_type":"text/plain"}
> ```
>
> 响应是**数组**，元素含 `document_id` / `url` / `owner` / `owner_id`。

### 任务

- [x] 探明 `/upload/web` 请求形状（D3b）
- [x] 拿到 `url` 后如何 POST 字节、如何提交记录（`/update/documents`）
- [x] 签名：确认 Drive 的 filesignature 语义（SHA-256 Base64）
- [x] **零内存流式**：`os.NewFile` + `io.Copy` / `ProgressReader`
- [x] 查重：Drive 靠 `etag` / `contentChangeTag`
- [x] 上传队列：**独立表 `drive_upload_queue`**，不进 `drive_sync_state`
- [x] 后台任务与通知：`DriveUploadWorker` + 常驻百分比进度通知
- [x] 上传与同步共用 `DataMutationLock`

### 验收标准

| 项 | 标准 |
| --- | --- |
| 小文件 | 上传后网页端可见、可下载、内容一致 |
| 大文件 | 分片完整（>100MB），无 OOM |
| 幂等 | 同一文件传两次不产生重复条目 |
| 断网 | 退避重试，不误判为鉴权失败（不触发会话重建） |
| 登出 | 上传中途登出立即放弃，且不写回数据 |

### 7.1 实测结论（2026-11，实机 SM-F9760）

> **已归档：[ISS-049](CHANGELOG_ISSUES.md#iss-049-drive-上传卡在传输中数分钟哈希阶段零进度上报) /
> [ISS-050](CHANGELOG_ISSUES.md#iss-050-drive-上传双引擎并跑同一任务重复上传与等待重启循环) /
> [ISS-051](CHANGELOG_ISSUES.md#iss-051-drive-上传通知卡在-18-后跳到-100通知更新被系统合并)。**

| 能力 | 状态 | 备注 |
| --- | --- | --- |
| 零内存流式上传 | ✅ | 单次 full-body POST |
| 分阶段进度（哈希/传输/提交） | ✅ | Go 侧 `Phase` 字段驱动 |
| 单引擎队列 + 前台服务 | ✅ | Worker 唯一 drain，共享 `uploadDrainMutex` |
| 通知实时进度 | ✅ | 500ms 采样，值变才 `notify` |
| 同名冲突提示 | ✅ | 409 `UNIQUE_FIELD_FAILURE` → “同名文件已存在” |
| **>1GB 文件** | ❌ | 见 §7.2，受 CDN 单次请求体限制 |
| **分片上传** | ❌ | **已证伪**（§7.3）：分片不能拼装，且单 token 限 3 片 |
| **断点续传** | ❌ | 未知；取决于是否找到真正的分片接口 |

### 7.2 >1GB 上传：必须分片（已定性）

实测 1.2 GB 文件在 **1.4 秒**内失败：

```
CWS streaming upload failed with HTTP 413: <html><title>413 Request Entity Too Large</title>
```

关键推论：

1. **不是 CloudDocs 的业务限制。** `/upload/web` 申请令牌**成功**了（否则拿不到 `url`）。
2. **是单次请求体的上限。** 1.4 秒返回意味着只看了 `Content-Length` 就拒，**没有传字节**。
3. **网页端能传 1.9 GB，因为它分片。** 同账号存在一个 1.9 GB 文件，对照成立。

> [!CAUTION]
> **不要相信“1GB 限制”这个说法。** 它是 ISS-046 写下的错误文案，导致后续排查往错方向走了很久。
> 限制来自 CDN 对**单个请求**的大小，与账号/目录/文件类型无关。

### 7.3 分片探针结论（2026-11 实测，已闭环）

> 探针代码已删除（`probe_drive_chunk.go` / `DriveChunkUploadProbeTest`）。
> 完整日志见 [ISS-053](CHANGELOG_ISSUES.md#iss-053-drive-分片上传探针content-range-可用但分片不能拼装)。

**实验一：同一 token 能否多次 `Content-Range` POST？——能。**

| 试探 | HTTP | 耗时 | 服务端存储 |
| --- | --- | --- | --- |
| 前 50 MB | 200 | 7.26s | 52,428,800 ✅ |
| 后 50 MB（同一 token）| 200 | 7.58s | 52,428,800 ✅ |

**实验二：分片能否拼成一个完整文件？——不能。**

| 提交方式 | 结果 |
| --- | --- |
| 声明 `size = 94,371,840`（完整文件）| ❌ 400 `bad upload receipt (did_not_validate)` |
| 声明 `size = 31,457,280`（单分片真实大小）| ✅ **200，创建了一个 30 MB 文件** |

**另外发现：`Content-Range` 是每段独立的 asset。**

- 每个分片返回**不同的 `fileChecksum`**（`ATwgNR6...` / `AXHE3304...` / `AU7OF1R7...`）。
- `receipt` 绑定的是**那一次请求实际收到的字节数**，所以拿完整文件大小去提交必然校验失败。
- 同一内容重传是**幂等**的（两次得到同一个 `fileChecksum`），这对断点续传有利。

> [!CAUTION]
> **每个 token 只接受 3 次 `Content-Range` POST，第 4 次返回 429。**
> 两次独立实验（含 60s 冷却后）**都在同一索引失败**，所以是**按 token 计数**，不是全局频率限制。
> 这意味着“用一个 token 传完 1.2 GB”这条路**直接堵死**（要么 3 片以内，要么换 token）。

### 7.4 结论（已定案，经三账号对照抓包）：**1 GiB 限制是云上贵州区特有，不是协议限制**

> [!IMPORTANT]
> **本节曾两次给出错误结论，现以三份 DevTools 抓包定案。**
> 完整对照数据与错误复盘见 [ISS-057](CHANGELOG_ISSUES.md#iss-057-drive-上传上限是云上贵州区特有国际区无此限制)。

**核心对照实验：同一个文件、同一套协议、三个账号**

文件：`arknights-hg-2761.apk`，**2,109,579,544 字节（1.96 GiB）**

| 账号 | 上传主机 | `singleFileUpload` 结果 |
| --- | --- | --- |
| 云上贵州账号1 | `cws.icloud-content.com.**cn**` | ❌ **413** |
| 云上贵州账号2 | `cws.icloud-content.com.**cn**` | ❌ **413** |
| **国际区** | `cws.icloud-content.com` | ✅ **200，2.1 GB 完整上传** |

三个账号走的**请求协议完全相同**：`/upload/web` → `singleFileUpload` → 单次 POST。
**没有任何分片、没有任何特殊端点。** 唯一的差别是账号所在区域。

国际区后端响应头：`x-responding-instance: contentws:...:prod-p00-contentws-uswest2a-95`（美国西部）。

**云上贵州账号1 内部的分界线（同一账号）：**

| 文件大小 | 结果 |
| --- | --- |
| 364 MB | ✅ 成功 |
| 790 MB | ✅ 成功 |
| 1.49 GB | ❌ 413 |
| 2.11 GB | ❌ 413 |

与本项目在设备上的实测（**1024 MB ✅ / 1100 MB ❌**）完全吻合。

**已尝试并证伪的绕过方案（对云上贵州无效）：**

| 方向 | 结论 |
| --- | --- |
| 定长分片 + 逐段 `Content-Range` | ❌ 每片是**独立 asset**，完整大小提交报 `bad upload receipt` |
| 单 token 多片 | ❌ 第 4 片就 429（按 token 计数）|
| 换上传模式字段（`chunked`/`resumable`/`partSize`…）| ❌ 全部仍返回 `singleFileUpload` |
| `type:PACKAGE` → `packageUpload` | ❌ 同样 413（同一前置限制）|

> [!CAUTION]
> **不要再写“分片突破 1GB”的实现。** 限制在**账号/区域服务端**，客户端无论怎么改都绕不过。
> 网页端同样传不了——已抓包证实（网页端只会调 `retryUpload.js` 重试，不换方式）。

**因此项目只需区分处理：**

- **云上贵州区**：>1 GiB 必然失败 → 提前拦截 + 准确提示
- **国际区**：无此限制 → 正常单次 POST 即可，**不要**加任何人为大小上限

### 7.5 待办

- [x] 根据**当前账号区域**决定是否启用 1 GiB 预检（不能全局硬编码）([ISS-057](CHANGELOG_ISSUES.md#iss-057-drive-上传上限是云上贵州区特有国际区无此限制))
- [x] 如何判定区域：检查 `docsEndpoint` 是否为 `.com.cn` 域名 ([ISS-057](CHANGELOG_ISSUES.md#iss-057-drive-上传上限是云上贵州区特有国际区无此限制))
- [x] 云上贵州区 >1 GiB 时**提前拦截**，在选文件后立即提示 ([ISS-057](CHANGELOG_ISSUES.md#iss-057-drive-上传上限是云上贵州区特有国际区无此限制))
- [x] 云上贵州账号首次点击上传按钮提示弹窗 ([ISS-059](CHANGELOG_ISSUES.md#iss-059-drive-上传失败任务支持重试与云上贵州账号首次上传-1gb-限制预警弹窗))
- [x] 失败任务支持列表点击重试 ([ISS-059](CHANGELOG_ISSUES.md#iss-059-drive-上传失败任务支持重试与云上贵州账号首次上传-1gb-限制预警弹窗))

---

## 8. D6 · 目录操作与冲突处理

### 任务

- [ ] 新建文件夹、重命名、移动、复制
- [ ] 删除（软删除 → 最近删除）
- [ ] **冲突语义必须实测**：同一父目录下同名文件 Apple 端如何表现？
  （自动改名 / 覆盖 / 报错）—— D0 未探明前不设计
- [ ] **本地优先 + 乐观更新，失败回滚**
  - 顺序：① 云端成功 → ② 立即更新本地 + 重建受影响层级 → ③ 不要只改云端
  - 参照 AGENTS §12 对「删除照片」的顺序要求（顺序不能倒）

### 验收标准

| 项 | 标准 |
| --- | --- |
| 各操作 | 在网页端能观察到对应结果 |
| 失败回滚 | 断网下操作，UI 回滚且给出提示 |
| 同名冲突 | 行为与 Apple 原生客户端一致（以实测为准） |

---

## 9. D7 · 预览与分享 ✅ 已完成

- [x] 图片 / 视频：复用现有 Coil（`ICloudImageLoaderFactory`）+ ExoPlayer 栈
  - **已显式传 `imageLoader`**（`DriveMediaViewer.kt`，遵循 AGENTS §6.6 硬规则①），避免缺漏 keyer/interceptor 导致静默失败
  - ExoPlayer 播放器自带生命周期 `DisposableEffect` 回收
- [x] PDF / 文本及常规文件：系统组件或轻量渲染
  - 未下载文件先拉取到沙盒缓存 `cache/drive_preview/` (`DrivePreviewCache`)
  - 通过 `FileProvider.getUriForFile` 拿到带有只读授权的 safe URI，发出 `Intent.ACTION_VIEW` 调起系统或第三方组件渲染预览
  - iWork 文档包 (`.pages`, `.numbers`, `.key`) 提供 `DriveIWorkWebDialog` 在线网页嵌入预览
- [x] 缩略图（Thumbnail）：
  - 接入 Go 侧 `thumbnail_token` 直链，本地 `cache/drive_thumbs` / `drive_preview` 双层缓存，网格/列表展示
- [x] 分享 / 导出：`FileProvider` + `ACTION_SEND`
  - Manifest 配置权威名 `app.acloudroid.qwq.fileprovider` 及 `file_paths.xml`
  - `DriveViewModel.shareItem` 生成带读授权 Safe URI 并发出 `Intent.ACTION_SEND` 呼出系统原生分享 chooser

---

## 10. D8 · 跨功能收尾 ✅ 已完成

- [x] **退出登录四阶段清洗已扩展到 Drive**
  - 顺序固定：**阻断 → Drive Room + Photo Room → Coil → Cookie/Vault**
  - `AuthViewModel.logout()` 阶段 0 取消 `DriveUploadWorker` 任务；`DriveDownloadQueue.clearAll()` 同步取消底层 Go 流与协程
  - `isWiping` 哨兵位已阻断 `drainUploadQueue()` 与 `enqueueDownload()`
  - `DriveRepository.wipeAllData()` 清空 `drive_items` 与 `drive_upload_queue` 全量表，并递归清理 `drive_downloads` / `drive_preview` / `drive_uploads` / `drive_thumbs` / `drive_icons` 缓存目录
- [x] Drive 相关设置项进**同一个设置页**（`SettingsCatalog` 三步走：`DRIVE_DISPLAY_MODE` / `DRIVE_SORT_FIELD` / `DRIVE_SORT_ORDER`）
- [x] 更新 [TODO.md](TODO.md) 的 Phase 5 状态为 ✅ 完成

---

## 11. 开工前必须想清楚的 4 个决策

> [!IMPORTANT]
> **① `dataMutationMutex` 的所有权是分水岭。**
> 它现在是 `PhotoRepository` 私有字段。Drive 一接入就必须提取成中立设施，
> 否则会复制出第二把锁 —— 而 AGENTS §12 明确要求「共用，不要另起」。
> **建议在 D2 编码前完成，那时改动面最小。**

> [!WARNING]
> **② 「目录 vs App 容器」必须在 D2 的 schema 层区分 —— 但字段是 `type`，不是 `bundleType`。**
>
> D0 实测：`bundleType` **不存在**，节点用 `type ∈ {FOLDER, FILE, APP_LIBRARY}` 区分。
> 且 `APP_LIBRARY` 也不是 iWork 文档包，而是 App 文档容器（详见 [REFERENCE.md](REFERENCE.md) §5.1）。
>
> iWork `.pages` 文件在列举中真实表现仍待补验，**不要把未验证的假设写进 schema**。

> [!NOTE]
> **③ 第一版是否要「离线镜像 / 整文件夹同步」？建议：不做。**
> Photos 是「云端为主、本地只是缓存」。Drive 用户很容易期望「把整个文件夹下到本地」，
> 那是另一套东西（批量下载队列 + 磁盘配额 + 双向冲突解决）。
> **建议第一版只做按需下载**，明确不做本地镜像，否则 D5~D8 做不完。

> [!CAUTION]
> **④ 复用 `ensureGoSession()` / `classifyCoreResponse()` / 三层刷新去重，不要复制粘贴。**
> 这三块是**唯一真理源**，且错误判定是踩过
> [ISS-013](CHANGELOG_ISSUES.md) / [ISS-028](CHANGELOG_ISSUES.md) 才收敛出来的。
> 复制一份意味着以后改一处要改两处。

---

## 12. 阶段验收的通用纪律

每个阶段收尾都必须走完下面这套（AGENTS §9）：

1. 改了 Go → `GOSUMDB=off ./scripts/build_aar.sh`
   （`sum.golang.org` 本机不可达，不加 `GOSUMDB=off` 会在 `go mod tidy` 失败）
2. `gradle_build(":app:assembleDebug testDebugUnitTest")`
3. **部署到设备并实际点击/滚动验证** —— 编译通过 + 单测绿**不等于**完成
4. `read_logcat` 确认无异常、无预期外的重复请求

### 性能基线（沿用 Photos 的标尺）

| 指标 | 期望值 |
| --- | --- |
| 冷启动 `Rebuilding Go core session` | ≤ 1 次 |
| 连续浏览 5 个目录的额外重建次数 | 0 次 |
| 同一 docwsid 5 分钟内 `download/by_id` | 1 次 |
| 目录切换主线程掉帧 | 无明显卡顿 |
| `FATAL EXCEPTION` / JNI 崩溃 | 0 |
