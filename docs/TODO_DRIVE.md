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
| D3 | 增量同步与对账 | D2 | ⏳ 未开始 |
| D4 | 下载（直链 + 落盘） | D2 | ⏳ 未开始 |
| D5 | 上传（分片） | D4 | ⏳ 未开始 |
| D6 | 目录操作与冲突处理 | D3 | ⏳ 未开始 |
| D7 | 预览与分享 | D4 | ⏳ 未开始 |
| D8 | 跨功能收尾 | D2~D7 | ⏳ 未开始 |

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

## 5. D3 · 增量同步与对账

> [!CAUTION]
> **本节的增量方案已经被 D0 实测推翻一半，开工前必须先做一轮侦察。**
>
> 原计划依赖 `changes/database`（与 `changes/zone` 两路），
> 但 D0 实测：`GET/POST {docws}/ws/com.apple.CloudDocs/changes/database`
> → `404 WSModuleNotFoundException: "Module changes could not be found."`
>
> 所以 **Drive 没有 CloudKit 式的 `changes/*` 增量接口**（至少不叫这个名字）。

### 5.0 先侦察，再实现（必需）

- [ ] **重新跑一轮探针**，按以下候选依次验证（每条都要实测，不许猜）：
  1. `GET /ws/com.apple.CloudDocs/changes/zone` —— 名字换了但机制相同？
  2. 根/目录列举的 **`etag` 比对** —— 最笨但确定能用
     （`root` 的 etag 实测为 `nrp`，是个短字符串，每次内容变化就变）
  3. `ckdatabasews` 的 CloudKit 层 delta（`com.apple.CloudDocs` 本质上也是 CloudKit zone）
  4. `/retrieveItemDetails`（不带 `InFolders`）是否返回更轻量的元数据
- [ ] 把结论补回 [REFERENCE.md](REFERENCE.md) §5

> [!TIP]
> 如果最终只能走「`etag` 比对」路线，那 D3 就退化为
> 「**目录级差量列举**」而不是「全局游标同步」：
> 每次只重列 `directChildrenCount` 或 `etag` 变了的目录。
> 对几千文件的量级这完全够用，而且比 `changes/*` 更好理解、更好调。

### 原来依赖 `syncToken` 的部分（保留，但适用范围待定）

- [ ] `syncToken` 持久化在 `drive_sync_state`（**仅当侦察确认存在游标时才需要**）
  - **不得复用 `scan_state`**（AGENTS §12 明令禁止跨功能复用游标表）
- [ ] 「是否走完」必须是**显式返回值**，不能靠函数正常返回
  （[PITFALLS §18.1](PITFALLS.md)：`ScanRunResult(scanned, walkCompleted)`）
- [ ] 对账闸门用**可推导判据**：`seenIdCount >= localCount`
  —— 禁止拍百分比（[PITFALLS §18.3](PITFALLS.md)：50% 阈值拦不住「只剩后半段」）
- [ ] 删除是两段式，**移入「最近删除」不会给 tombstone**
  （[PITFALLS §17](PITFALLS.md)）—— 需要显式拉取最近删除索引，与 Photos 同理
  - 但其接口名/位置 **需在 5.0 侦察中一并确认**（Drive 的回收站很可能又是一个独立 zone）
- [ ] 所有写操作持 `DataMutationLock`，并检查 `isWiping`
- [ ] 接入 `WorkManager`：区分「增量同步」与「全盘对账」两个入口，
  **全盘对账必须经过用户确认**（AGENTS §10.0：主页刷新只做增量）

### 验收标准

| 项 | 标准 |
| --- | --- |
| 新增文件 | 网页端新建 → 本地同步可见 |
| 重命名/移动 | 网页端操作 → 本地层级正确更新 |
| 删除 | 网页端删除 → 本地消失，且不会被写回 |
| 断点续扫 | 中途杀进程 → 重启后 `purged=0`，库完好 |
| 冷启动 | 不触发无谓的全量扫描 |

---

## 6. D4 · 下载（直链 + 落盘）

### 任务

- [x] `download/by_id` 拿 `data_token`（**已实测，格式见 [REFERENCE.md](REFERENCE.md) §5.5**）
  - `GET {docws}/ws/com.apple.CloudDocs/download/by_id?document_id=<docwsid>`
  - 返回 `data_token.url`（全文件）+ `thumbnail_token.url`（缩略图）
  - **无需**自己生成缩略图，D7 直接用 `thumbnail_token`
- [ ] **刷新逻辑复用 Photos 的模式，不要另写一套**
  - Go 侧新增 `RefreshDownloadURL(docwsid)`
  - Kotlin 侧复制 `refreshUrlsForMaster` 的**三层去重**结构：
    in-flight `Deferred` 合并 / 5 分钟窗口读库 / 会话刚重建时随机抖动
  - `URL_MAX_AGE_MS = 12min`（实测值，见 [AGENTS §6.1.1](AGENTS.md)）
- [ ] 落盘走 `MediaStore.Downloads`（非媒体文件），流式写入
  - **绝不用 `[]byte` 跨 JNI**（[PITFALLS §4](PITFALLS.md)）
  - 传文件路径或 FD 给 Go，由 Go 直接读写文件流
- [ ] 大文件断点续传：Drive 理论支持 `Range`，但**必须用 curl 实测后再写结论**
  （[PITFALLS §3](PITFALLS.md)：有效期/寿命类结论必须实测，别信文档）

### 验收标准

| 项 | 标准 |
| --- | --- |
| 小文件 | 下载完成，落盘于 Downloads，内容校验一致 |
| 大文件（>100MB） | 无 OOM，无 JNI 崩溃 |
| 直链过期 | 12 分钟后触发主动换链，不出现 410 才对用户报错 |
| 并发去重 | 同一 docwsid 5 分钟内只发一次 `download/by_id` |

---

## 7. D5 · 上传（分片，风险最高）

> [!CAUTION]
> **`/upload/web` 是分片协议，与 Photos 的单文件直传完全不同。禁止照搬 `upload.go`。**

### 任务

- [ ] D0 已探明分片流程后，再动手实现：
  filesize + filesignature → `uploadid` → 逐片 POST → 提交
- [ ] 签名：**先确认 Drive 的 filesignature 语义**（是否与 Photos 同为 SHA-256）
- [ ] **零内存流式**：`os.NewFile` + `io.Copy` 分片
  - **必须在流式上传前抽完全部需要的元数据** ——
    [ISS-040](CHANGELOG_ISSUES.md) 的 `uploadFileToUrl` 会关闭传入的 `*os.File`，
    后续任何 `ReadAt` 都会失败，且只表现为「字段缺失」
- [ ] 查重：Drive 靠 `etag` / `contentChangeTag`，比 Photos 的 MMCS 指纹简单
- [ ] 上传队列：**独立表 `drive_upload_queue`**，不进 `drive_sync_state`
  （AGENTS §12：上传统计不进扫描游标表）
- [ ] 后台任务：`DriveUploadWorker` + 前台服务 + 退避重试
  - 约束：仅 Wi-Fi / 充电 / 电量充足
  - 错误分类复用 `classifyCoreResponse()`，新增 Drive 错误码在 `errors.go` 注册
- [ ] 上传与同步共用 `DataMutationLock`

### 验收标准

| 项 | 标准 |
| --- | --- |
| 小文件 | 上传后网页端可见、可下载、内容一致 |
| 大文件 | 分片完整（>100MB），无 OOM |
| 幂等 | 同一文件传两次不产生重复条目 |
| 断网 | 退避重试，不误判为鉴权失败（不触发会话重建） |
| 登出 | 上传中途登出立即放弃，且不写回数据 |

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

## 9. D7 · 预览与分享

- [ ] 图片 / 视频：复用现有 Coil（`ICloudImageLoaderFactory`）+ ExoPlayer 栈
  - **必须显式传 `imageLoader`**（AGENTS §6.6 硬规则①），否则会静默失败且无日志
- [ ] PDF / 文本：系统组件或轻量渲染
- [ ] iWork 文档包缩略图：Drive 提供 thumbnail 端点，D0 探明后再实现
- [ ] 分享 / 导出：`FileProvider` + `ACTION_SEND`

---

## 10. D8 · 跨功能收尾

- [ ] **退出登录四阶段清洗必须扩展到 Drive**
  - 顺序固定：**阻断 → Drive Room + Photo Room → Coil → Cookie/Vault**
  - `isWiping` 哨兵位必须能阻断 Drive 的同步 / 上传 / 下载
  - 检查是否有**新的异步写回路径**（[PITFALLS §8](PITFALLS.md)：
    「只清不拦，等于没清」）
- [ ] Drive 相关设置项进**同一个设置页**（`SettingsCatalog` 三步走，AGENTS §10.0）
- [ ] 更新 [TODO.md](TODO.md) 的 Phase 5 状态
- [ ] 若过程中暴露通用性陷阱 → 补进 [PITFALLS.md](PITFALLS.md)；
  修复真实 bug → 在 [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md) 新增条并更新索引

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
