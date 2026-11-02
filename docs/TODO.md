# Android iCloud Photos Sync - 开发路线图 (TODO.md)

> **项目定位**：基于 `Go (gomobile .aar)` + `Kotlin (Jetpack Compose)` 的 Android 原生 iCloud 照片客户端与后台自动备份工具。
> **核心目标**：解决"Android 主力机 + 多台 Apple 设备"跨生态照片浏览与自动同步痛点。
> **核心架构**：Android WebView 混合鉴权 + Go 核心层（复用 `rclone` iCloud Session 底座 + 逆向 CloudKit `ckdatabasews` 协议）+ Android 原生系统接口（`MediaStore` / `WorkManager` / `Room`）。

## 📚 文档索引

| 文档 | 内容 |
| --- | --- |
| [README.md](../README.md) | 项目介绍、架构图、构建步骤 |
| [AGENTS.md](AGENTS.md) | AI Agent 协作规范、编译调试约定 |
| [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md) | **已修复问题案例库**（根因分析、修复方案、验证记录） |
| [docs/REFERENCE.md](REFERENCE.md) | 核心参考开源项目与源码对照表 |
| [docs/PITFALLS.md](PITFALLS.md) | 关键工程避坑指南（必读） |
| [docs/TODO_DRIVE.md](TODO_DRIVE.md) | **iCloud Drive 开发路线图**（Phase 5，D0~D8 阶段拆分） |
| [docs/ARCHIVE_PHASE1.md](ARCHIVE_PHASE1.md) | Phase 1 详细过程记录与修复方向存档 |

---

## 分阶段开发任务清单

### Phase 0: 跨语言基础工程与编译流水线搭建 ✅

- [x] **工程目录初始化**（Kotlin + Compose + Material 3 主工程、`/go-core` Go Module）
- [x] **打通 `gomobile` 编译链路**（`../scripts/build_aar.sh` 一键构建、JNI 异常捕获验证）

### Phase 1: 混合鉴权与国区/国际区动态路由 (MVP 底座) ✅

- [x] **Android 端：WebView 登录拦截与凭证提取器**
  - [x] 国区 / 国际区节点切换开关
  - [x] 加载官方登录页，用户完成密码 + 2FA（提示勾选"信任此浏览器"）
  - [x] 监听跳转后调用 `CookieManager.getCookie()` 提取完整 Cookie
  - [x] **强校验三要素**：`X-APPLE-WEBAUTH-TOKEN`、`X-APPLE-WEBAUTH-HSA-TRUST`、`X-APPLE-WEBAUTH-PCS-CloudKit`
  - [x] `EncryptedSharedPreferences` 加密持久化 Cookie 与节点类型
- [x] **Go 核心层：Session 接管与动态路由表解析**
  - [x] `InitSession(cookieStr, isChinaMainland)` JNI 接口
  - [x] `POST /setup/ws/1/validate` 完成会话握手
  - [x] 解析 `webservices` 提取 `ckdatabasews.url` 与 `pcsRequired`
  - [x] Cookie 解析剥离双引号、跨子域 `CookieMap` 手动注入

**实机验证结论**（emulator-5554 / API 37）

| 项目 | 结果 |
| --- | --- |
| Cookie 捕获 | ✅ 18 项，三要素齐全（TOKEN 154 / HSA-TRUST 265 / PCS-CloudKit 68 字符） |
| 区域路由 | ✅ 国区 `https://www.icloud.com.cn/` |
| 动态路由解析 | ✅ `dsid=20118126497`，`https://p203-ckdatabasews.icloud.com.cn:443` |
| 冷启动恢复 | ✅ 从加密存储静默恢复，无需重新登录 |

> [!NOTE]
> Phase 1 与 Phase 2 的所有已修复问题及案例闭环记录（ISS-001 ~ ISS-014）已完整收录并归档至 [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md)。

### Phase 2: 照片库拉取、全库月扫描与月分组本地缓存 (只读预览) ✅

- [x] **🔴 P0 阻塞 — 冷启动恢复跳过 Go 会话重建** ✅（已通过 `PhotoRepository.ensureGoSession()` 解决，详见 [ISS-012](CHANGELOG_ISSUES.md#iss-012-冷启动恢复跳过-go-会话重建导致照片接口全局报-no-active-session)）
- [x] **🟠 P1 — 会话守卫未下沉到 `PhotoRepository`** ✅（已将 `ensureGoSession(force)` 统一封装至 `PhotoRepository`，覆盖 `checkZoneReady` / `fetchScanPage` / `syncIncremental` / `refreshUrlsForMaster` 全部入口，支持 401/421 自动静默重建与 Token 自动续期；全库扫描走 `MonthScanEngine` 并复用同一守卫）
- [x] **🟡 P2 — 跨子域 Cookie 首次真实验证** ✅（已通过 `Session.NewRequest` 手动注入 CookieMap 验证通过）

- [x] **Go 核心层：CloudKit (`ckdatabasews`) 查询接口封装**
  - [x] 验证工作区状态：`{ckdatabasews.url}/database/1/com.apple.photos.cloud/production/private/zones/list`，确认 `PrimarySync` 分区就绪
  - [x] `FetchPhotoPage(continuationMarker string, limit int) (jsonResult string, err error)`
    - 路径：`POST {ckdatabasews.url}/database/1/com.apple.photos.cloud/production/private/records/query`
    - 解析 `CPLAsset`：`assetDate`、`masterRef`、`isFavorite`、`isHidden`、`duration`
    - 解析 `CPLMaster`：`recordName`、`itemType`、`resJPEGThumbRes`、`resJPEGMedRes`、`resOriginalRes`、`resOriginalVidComplRes`
- [x] **Go 核心层：增量同步 (Delta Sync)**
  - [x] 对接 `/records/changes`，保存并传入 `syncToken`，仅拉取增量变更
  - [x] 对接 `/records/lookup`，实现 `RefreshAssetUrls` 解决 1 小时直链过期失效
- [x] **Android 端：本地数据库索引与流畅画廊**
  - [x] `Room` 数据库（`PhotoAssetEntity(含 yearMonth)`、`SyncStateEntity`、`PhotoMonthIndexEntity`、`ScanStateEntity`、`PhotoDatabase`）
  - [x] `MonthScanEngine` 全库扫描 + `LazyColumn` 月分组展示（首屏秒开、动态 3/4/5 列缩放切换、年月导航条）
  - [x] 自定义 `Coil` `Interceptor` 与 `Keyer`（缓存 Key 绑定 `recordName`，按需检测直链过期自动刷新）
  - [x] `Media3 (ExoPlayer)` 云端视频流式播放 + 一键流式下载写入 `MediaStore`（规避 JNI OOM）

- [x] **🔴 P1 阻塞 — 每次分页都触发一次完整会话重建** ✅（修正 `isAuthError` 解析 JSON 错误码匹配避免误判数据，固化 Go 侧 `ClientID` 全生命周期复用，详见 [ISS-013](CHANGELOG_ISSUES.md#iss-013-isautherror-误判原始-json-字符串导致每次分页触发重复鉴权)）
- [x] **🔴 P1 阻塞 — 原图/视频详情页加载失败（HTTP 410）** ✅（`ICloudPhotoInterceptor` 和 `MediaStoreDownloader` 增加 HTTP 410/403 捕获与自动重试换链，详见 [ISS-014](CHANGELOG_ISSUES.md#iss-014-直链失效捕获重试与-clientid-游标关联断开)）
- [x] **🟠 P2 — `ICloudPhotoCoil` 无任何日志** ✅（已在 `ICloudPhotoInterceptor` 中增加结构化日志输出）
- [x] **🟠 P2 — 视频详情页时长显示 00:00** ✅（已在 `VideoPlayerView` 添加 `Player.Listener` 错误监听并向用户展示清晰引导）
- [x] **🟡 P3 — 分页日志刷新刷屏** ✅（已降级无害频繁日志至 `Log.d`）

#### 第三轮真机联调收尾（三星 SM-F9760）

- [x] **`append` 指示器作为网格项导致跳顶** ✅（footer 移出网格 + 网格常驻组合树，详见 [ISS-019](CHANGELOG_ISSUES.md#iss-019-append-指示器作为网格项导致列表跳回顶部)）
- [x] **分页期间会话重建回归（17 秒 4 次）** ✅（`continuationMarker` 失效路径作废，详见 [ISS-020](CHANGELOG_ISSUES.md#iss-020-continuationmarker-失效后仍被重放引发-401--重建死循环)）
- [x] **冷启动主线程阻塞 ~3.3 秒** ✅（`AuthUiState.Checking` 初始态避免 WebView 提前构造，详见 [ISS-022](CHANGELOG_ISSUES.md#iss-022-冷启动时-login-webview-被提前构造阻塞主线程-33-秒)）
- [x] **HDR gain map HEIC 解码失败 + 解码失败误触发刷新** ✅（`ImageDecoderDecoder` + `force` 刷新通道，详见 [ISS-023](CHANGELOG_ISSUES.md#iss-023-hdr-gain-map-heic-解码失败--解码失败误触发-url-刷新)）
- [x] **Go 层 `time.Now()` 作排序键兜底** ✅（改为稳定 `0`，避免照片位置漂移）
- [x] **排序键不稳定** ✅（`ORDER BY assetDate DESC, id ASC` + 复合索引）
- [x] **退出登录不清洗数据层（P2-018）** ✅（`logout()` 四阶段清洗：阻断 → Room → Coil → Cookie/Vault，并修复 `removeAllCookies` 未等回调就 `flush()` 的缺陷，详见 [ISS-025](CHANGELOG_ISSUES.md#iss-025)）
- [x] **Cookie 顺序抖动 + 错误失效假设（P2-019）** ✅（Go 侧 `BuildCookieHeader` 排序 + Kotlin 侧集合比较；实测推翻「会话重建即失效」，URL 真实寿命 ~15-18 分钟而非 1-2 小时，`URL_MAX_AGE_MS` 45min→12min，代际不再由 Cookie 变化触发 bump。详见 [ISS-026](CHANGELOG_ISSUES.md#iss-026)）
- [x] **深翻页跳顶（P2-016）** — ✅ **已根治**：彻底移除 Paging 3 `RemoteMediator`，改为月分组 + 行展开的本地分页，滚动锚点由全局 index 改为 `monthKey`。详见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)
- [x] **Go 层结构化错误字段（P2-015）** — ✅ **已修复**：新增 `errors.go`（`errorCode` + `httpStatus`），Kotlin 侧收敛为 `classifyCoreResponse()`。详见 [ISS-028](CHANGELOG_ISSUES.md#iss-028-go-层缺结构化错误字段导致-kotlin-靠正则猜)

#### 架构重构：多功能 iCloud 客户端演进 (全功能 App 骨架) ✅

- [x] **手写 AppContainer 代替分散单例** ✅（创建 `AppContainer` 与 `IClouDroidApp`，统一注入 `SessionManager`、`PhotoDatabase` 与 `PhotoRepository`）
- [x] **模块化包结构重构** ✅（拆分出 `feature/photos/` 自包含包，将数据/数据库/UI/Coil代码完全内聚）
- [x] **数据库隔离设计** ✅（`AppDatabase` 重命名为 `PhotoDatabase`，专库专用，避免未来扩展 Drive/Notes 拖累照片表）
- [x] **AppShell 壳层与多 tab 导航** ✅（新增 `AppShell` 底部导航，预留 Drive 与备忘录占位入口 `AppDestination`）
- [x] **Context 容器定位自愈** ✅（`Context.appContainer()` 优先使用 `applicationContext`，解决 Compose 上下文层级查找崩溃）

**Phase 2 收尾验证指标**（模拟器 Medium Phone API 37）：

| 指标 | 结果 |
| --- | --- |
| 冷启动会话重建 | **0 次** |
| `auth error` | **0 次** |
| WebView 冷启动初始化 | **0 次** |
| `Choreographer: Skipped` | **无** |
| 滚动中 `itemCount` 塌陷 | **0 次** |
| `go test ./...` | **ok** |
| `testDebugUnitTest` | **70 passed, 0 failed** |

**P2-019 修复后补充验证**（物理机 SM-F9760）：

| 指标 | 结果 |
| --- | --- |
| 冷启动 + 强制重建的 generation bump | **0 次**（修复前每次 +1） |
| 首屏 + 滚动 3 屏的 Coil 加载失败 | **0 次** |
| 42 分钟老 URL | 主动 `refreshed` 换链，无需等 410 |

**UI 重构（月分组）后验证**（物理机 SM-F9760，15,936 项 / 108 个月）：

| 指标 | 结果 |
| --- | --- |
| 30 次极速上滑（30ms） | 内容完整渲染（截图 2.37MB） |
| 反向 30 次极速上滑 | 内容完整渲染（截图 2.37MB） |
| 月份 `isComplete` 盖章 | 108/108 全部为 1 |
| `yearMonth = 0` 滞留行 | **0** |
| 年份跳转（→2020） | 正确落位 |
| 后台扫描崩溃 | **0 次** |
| `FATAL EXCEPTION` | **0** |

#### 照片详情面板与现代化查看体验 ✅

- [x] **Go 层：EXIF/GPS 解析** — 引入 `howett.net/plist`，新增 `metadata.go`
  （`FetchPhotoMetadata`，base64(bplist) → 扁平 JSON，含南北纬/东西经符号处理）
- [x] **数据层：元数据缓存** — `PhotoAssetEntity.exifJson/exifFetchedAt` + Room `MIGRATION_4_5`
  + `PhotoRepository.fetchMetadata()`（24h TTL、并发去重、负缓存）
- [x] **双层画质过渡** — 底图复用网格 THUMB 缓存（`cacheOnly` 旁路，零网络零刷新）
  → 上层 MEDIUM/ORIGINAL 淡入，不再出现加载圈
- [x] **全屏查看器** — `PhotoViewerScreen`（`HorizontalPager` 月内左右滑动、图/视频混排）
  + 窗口化 `ExoPlayer`（仅当前页持有解码器）
- [x] **信息卡片** — `PhotoInfoCard`，按字段存在性逐级降级；无 GPS 则整组隐藏；
  截图只显示文件行 + 提示文案，**永不空白**
- [x] **全屏与沉浸模式** — 全屏保留状态栏；单击切换沉浸（隐藏状态栏 + 全部 chrome）
- [x] **手势** — 双指缩放（以手势中心为锚点）、双击缩放（弹簧动画）、下滑退出、
  上滑开详情、左右翻页（位移 1/4 屏 **或** 速度 > 600 px/s）
- [x] **“从哪里来回哪里去”动画与全屏查看器** — 完整修复，共 4 轮迭代
  - **第1轮** `rememberPagerState` 缓存：重打开固定在第一张 → `key(currentPhotoId)` 隔离 slot
  - **第2轮** 翻页后关闭回错格子 → `LaunchedEffect(currentAnchor)` 跟随 `settledPage`；
    首帧 `windowSize` 零尺寸闪烁 → 用 `LocalConfiguration` 预初始化
  - **第3轮** 动画生硬 → `offset+size` 每帧重新测量改为 `graphicsLayer` 变换（缩放+平移，
    pager 保持全窗口布局，每帧只变两个 float）；`spring` 改为 320ms `tween`；
    **背景不再黑屏** → 转场状态上提到 `PhotosScreen`，网格与查看器共用同一 `progress`
    做互相淡入淡出；查看器背景遮罩封顶 0.72，永不完全不透明
  - **第4轮** 底栏不隐藏 → `AppShell` 去掉 `Scaffold.bottomBar`（切换该 slot 会让 Compose
    重置该 slot 内子树的 state，`viewerOpen` 变 stale），改为普通兄弟节点 + 手动预留 80dp

> [!IMPORTANT]
> **`Scaffold` 的 `bottomBar` / `topBar` 槽位不能做 `if` 条件切换。** 该 slot 的内容一变，
> Compose 会按 movable-content 规则重置其中子树的 remember 状态，闭包里看到的标志位
> 会变成stale 值。本项目已因此出现过“底栏挡住信息/下载按钮”的真实 bug。
> 正确做法：把条件渲染的兄弟节点放在 `Box` 里，需要留白就手动 `PaddingValues` 传入。

> [!WARNING]
> **转场动画必须用 `graphicsLayer`，不能用 `Modifier.offset` + `Modifier.size`。**
> 后者会改布局尺寸，全屏 pager 每帧重新测量，动画必卡。
> 另需注意 `graphicsLayer` 默认轴心在中心，必须 `transformOrigin = TransformOrigin(0f, 0f)`，
> 否则照片会从中心而非缩略图位置长出。

**模拟器验证**（emulator-5554，15,936 项真实图库）：

| 指标 | 结果 |
| --- | --- |
| EXIF 解析 | ✅ `Apple iPhone SE (3rd)` / `1/21s · f/1.8 · 3.99 mm · 28 mm 等效` / `ISO 640` |
| GPS | ✅ `35.955330, 120.171417` / `27.3 m · 15° 北` |
| 无 EXIF 降级 | ✅ 截图仅文件行 + 提示，无空占位 |
| `/records/lookup` 去重 | ✅ 2 张 = 2 次，重复打开不新增 |
| 会话重建 / 崩溃 / MediaCodec 错误 | ✅ 1 次（冷启动） / 0 / 0 |

> [!NOTE]
> 以下仍需**实体机**确认：画质过渡实际帧率（模拟器软解不可信）、HDR 高位深降级链
> （ISS-023 回归）、大图库内存与 I/O 压力。

#### 双向删除同步与全量对账（ISS-030 / ISS-031）✅

前两轮修复解决了“只增不减”的根本问题：**本地图库现在能感知云端删除，也能证明某张照片真的不存在了。**

- [x] **ISS-030：软删除与孤儿 master**
  - `defaultDesiredKeys` 补上 `isDeleted`（白名单漏一个字段，判断分支永远走不到）
  - 删除「孤儿 `CPLMaster` 伪造成照片」的逻辑（回收站照片的 master 有 30 天保留期）
  - 新增 `FetchRecentlyDeleted()`，recordType 为 `CPLAssetAndMasterDeletedByExpungedDate`
  - `deletedIDs` 同时收集 `recordName` 与 `masterRef`
- [x] **ISS-031：全盘对账**
  - Go：`PhotoPageResult` 透出 `DeletedIDs`
  - Room v6：`scan_seen_ids`（本轮见过的 id + masterId）、v7：`purged_ids`（持久化已删记忆）
  - `MonthScanEngine`：`runScanLoop` 返回 `ScanRunResult(scanned, walkCompleted)`，
    `walkCompleted = true` 全代码唯一赋值点 = 云端不再给 `continuationMarker`
  - `Reconcile.decide()`：保守规则 —— **只要 `masterId` 仍在云端返回，本地行全部保留**
  - `dataMutationMutex`：对账 / 增量同步 / 回收站清理共用一把锁
  - 修复多 `CPLAsset` 串写：`updateUrls` 由 `WHERE masterId=` 改为 `WHERE id=`，
    `getPhotoByMasterId(LIMIT 1)` 改为 `getPhotosByMasterId(全组)`

**真机验证**（SM-F9760）：

| 指标 | 结果 |
| --- | --- |
| 完整对账日志 | `reconcile: seen=31795 local=15937 purged=22 keptBySeen=15937` |
| `photo_assets` / `month_index` 求和 / `scannedCount` | **15937 / 15937 / 15937** 三者一致 |
| 冷启动 + 手动同步后复活数 | **0** |
| 同一 master 3 行样本 | **3 行全保留**（保守策略生效） |
| 断点续扫（中途杀进程）后对账 | `purged=0`，库完好 |
| `go test` / `testDebugUnitTest` | 全绿 / **137 项** |

> [!CAUTION]
> **实施中自己踩过的坑（已修复，已在 [ISS-031](CHANGELOG_ISSUES.md#iss-031) 完整复盘）：**
> 首次验证时中途杀进程触发**断点续扫**，对账误删了 **700 张真实照片**。两层原因：
> ① `scanAll` 每次启动都清空 `scan_seen_ids`，而续扫只覆盖剩余页 → `seen` 只剩后半段；
> ② 安全闸门是“≥ 本地 50%”，而“只剩后半段”恰好高于 50%，没拦住。
> 修复后：scratch 表**仅在全新一轮（`lastMarker == null`）时清空**；闸门改为可推导的
> **`seenIdCount >= localCount`**（完整 walk 必然成立，不成立即为 id 集不完整的铁证）。
> 700 行已由后续全量扫描从云端完整回灌 —— 这正是“对账只删本地缓存、从不调云端写接口”的价值。

#### 设置页与周期扫描 ✅

- [x] **设置页**（`ui/settings/`，入口在「账号信息」sheet 的**设置**按钮）
  - `SettingsCatalog` 声明式登记分组与顺序，`SettingsItem` 是 enum，`SettingsRow` 的 `when` 穷尽
  - **新增设置项只改三处**（枚举 / 分组 / 渲染分支），漏写分支会编译失败
  - 存储统一走 `AppSettings`（`SharedPreferences` + `StateFlow`）
- [x] **全盘扫描入口从主页迁到设置页**
  - 主页刷新按钮**只做增量同步**（`syncPhotos()`），不再触发全盘扫描
  - 设置页点击全盘扫描先弹警告：「不要中途强行关闭 App」「中断后本次扫描不生效」
    「扫到一半退出不损坏数据，下次从断点继续」
- [x] **周期扫描频率**：日 / 周 / 月 / 关闭 / 自定义（1~365 天，预设 1/2/3/7/14/30/60/90/180）
  - 预设全部对齐**凌晨 3:00**（`ScanCadence.delayUntilPreferredHour`），首次运行延迟到下一个 3 点
  - `PeriodicScanScheduler` 使用 `ExistingPeriodicWorkPolicy.UPDATE`（用 `KEEP` 改设置会静默失效）
  - 与一次性补扫 `ScanScheduler` **使用不同的 uniqueWorkName**（共用会互相取消）
- [x] **overlay 返回手势**：设置页接 `PredictiveBackHandler`（跟随手指）+ `BackHandler`（兜底）
  - 侧边手势 / 左上角箭头 / 返回键 **三条路径行为一致**
  - 修复前侧边手势会直接退出应用（overlay 不是 Activity，系统不知道上面还有一层）

**真机验证**（SM-F9760）：

| 指标 | 结果 |
| --- | --- |
| 周期任务入队 | `interval_duration=604800000`（每周），状态 ENQUEUED |
| 首次运行延迟 | `first run in 399 min`（20:20 → 次日 03:00）✅ |
| 冷启动恢复 | `Periodic scan scheduled: CUSTOM (14d)` ✅ |
| 主页刷新日志 | 仅 `Starting incremental sync`，无 `MonthScanEngine` 活动 ✅ |
| 侧边手势拖满 / 拖一半取消 / 箭头 / 返回键 | 全部正确，`topResumedActivity` 始终 MainActivity ✅ |


### Phase 3: Android 本地相册自动备份 (增量上传) 🟡 (底层已打通，UI 与控制策略开发中)

> [!NOTE]
> **Phase 3 进度**：Phase 3.1 协议与底层数据工程（Go 核心上传、FD 零内存流式上传、Room v9 队列、`scan_seen_ids` 对账保护）已全量落地并通过测试；**Phase 3.2 备份 UI 与高级控制策略正在开发中**。

#### Phase 3.1: 协议与数据底层工程 ✅
- [x] **协议验证与探针闭环**
  - [x] `/assets/upload`（SHA-256 签名）→ `singleFileUpload` 直链
  - [x] `*os.File` 零内存流式 POST（16MB 图 / 105MB 视频均 `200 OK`，**免分片**）
  - [x] `/records/modify` 原子提交 `CPLMaster`（`recordName = fileChecksum`）+ `CPLAsset`
  - [x] 回环验证：上传后 `syncIncremental()` 能把新 asset 拉回 `photo_assets`
- [x] **Go 核心层：上传链路工程化 (`go-core/upload.go`)**
  - [x] 新增 `go-core/upload.go`：接收文件描述符（`fd`）或路径，使用 `os.NewFile` + `io.Copy` 零内存流式哈希，`http.NewRequest(POST, uploadUrl, *os.File)` + 显式 `ContentLength` 流式上传
  - [x] 导出 `UploadPhotoAsset(fd, filename, mimeType, metadataJson)`，返回 JSON 内嵌 `ResultBase`
  - [x] `CPLAsset.assetDate` 提取 EXIF `DateTimeOriginal`，保证相册时间线准确
- [x] **查重（两阶段防死锁机制）**
  - [x] 第 1 层：前置基于 `localSha256` 查本地 `upload_queue`
  - [x] 第 2 层：后置在收到 `fileChecksum` 后查 `photo_assets.masterId`，命中即跳过 `/records/modify`，避免重复资产
- [x] **数据层与安全**
  - [x] Room **v9**（`MIGRATION_8_9`）新增 `upload_queue` 表
  - [x] 增量同步/上传入库接入 `scan_seen_ids` 保护，防止被月扫描对账引擎误删
  - [x] 所有写入持 `PhotoRepository.dataMutationMutex`，且各环节检查 `isWiping` 阻断点
- [x] **鉴权强校验**
  - [x] `GetSessionStateSummary` 与上传守卫强校验 `X-APPLE-WEBAUTH-PCS-Photos`
- [x] **Android 端：后台任务调度 (`WorkManager`) & Scoped Storage**
  - [x] `PhotoBackupWorker` 基础后台上传任务与约束
  - [x] 使用 `MediaStore.setRequireOriginal(uri)` 保持原始 GPS 坐标，通过 `openFileDescriptor(uri, "r")?.detachFd()` 向 Go 层传递 FD

#### Phase 3.1b: 上传记录 Apple 端可渲染性 ✅ (ISS-040)
- [x] **`res*FileType` 改为 UTI**（`public.heic` / `public.jpeg` / `public.mpeg-4`）—— 写 MIME 会导致**全部**上传项在 Apple 端灰块/感叹号
- [x] **重写 `mediaMetaDataEnc` 编码**：改用 `howett.net/plist` + 写入前 `Unmarshal` 自校验（旧手写 bplist 结构损坏，`plutil` 直接拒解）
- [x] **视频 `AVAssetProxy`**：抽 `ftyp`+`moov` 盒 → `{moop, mpvr, name}`，修复「时长显示 0:00」
- [x] **视频 JPEG 派生图**：`getFrameAtTime` 首帧 → 320/2048，修复 Apple 端无 poster frame 与 App 内灰底「iCloud」
- [x] **派生图方向**：图片按 EXIF 物理旋转；视频用帧宽高比自检是否需旋转（避免 `getFrameAtTime` 已旋转时再转一次）
- [x] **补齐字段**：`fullSizeJPEGSource` / `importedBy` / `importGroupId` / `assetSubtypeV2` / `customRenderedValue` / `timeZoneNameEnc` / `timeZoneOffset` / `codec` / `videoFrameRate`
- [x] **`assetId` 改标准 UUID v4**（原 `ASSET-<纳秒>` 会泄漏进网页 URL）
- [x] **`/records/modify` 解析 `serverErrorCode`**（CloudKit 200 也可能是失败）
- [x] **上传幂等查重**：`masterRecordExists()` 在建记录前 lookup `recordName = fileChecksum`，命中则返回 `duplicate: true`
- [x] **清理既有重复行**：`PhotoDao.deleteDuplicateMasterRows()`，扫描前自动执行
- [x] **`localSha256` 回写** `upload_queue`（此前永远是空串，第 1 层查重形同虚设）

#### Phase 3.2: 备份 UI 界面与高级控制策略 ⏳
- [ ] **相册分文件夹备份管理 UI**
  - [ ] 在 `LocalAlbum` 相册列表与详情页提供“开启自动备份”开关（持久化 `isBackupEnabled`）
  - [ ] 仅针对开启备份的相册文件夹（`BUCKET_ID`）扫描并入队
- [ ] **实时备份进度与状态面板**
  - [ ] 在主界面 / 侧边栏添加“备份状态栏”：显示待上传数量、当前上传文件名、进度百分比与上传速度
  - [ ] 队列管理 Sheet：查看排队列表、重试失败任务、手动暂停/恢复备份
- [ ] **智能电池与网络控制策略**
  - [ ] 低电量自动暂停（WorkManager `setRequiresBatteryNotLow(true)`）
  - [ ] 仅充电时备份开关（WorkManager `setRequiresCharging(true)`）
  - [ ] 蜂窝移动网络上传开关（默认仅限 Wi-Fi，允许用户开启移动网络备份）
- [x] **Live Photo / 实况照片打包上传** (Phase 4 衔接，详见 [ISS-044](CHANGELOG_ISSUES.md#iss-044-motion-photo--live-photo-自动拆分与双资源-cloudkit-备份上传))
  - [x] Room **v10**（`MIGRATION_9_10`）新增 `upload_queue` 的 `isMotionPhoto` / `videoOffset` / `videoSize`
  - [x] XMP 检测与全格式 HEIC 直扫支撑（[MotionPhotoDetector.kt](file:///Users/syh/iClouDroid/app/src/main/java/com/getup/iClouDroid/feature/photos/data/MotionPhotoDetector.kt)）
  - [x] 本地双资源拆分与 MMR 时长/中点计算（`extractMotionPhotoComponents`）
  - [x] Go 核心层 CloudKit 协议组装上传（`resOriginalVidComplRes` + `assetSubtypeV2 = 2` + `mediaGroupId`）
- [ ] **鉴权前置**
  - [ ] `GetSessionStateSummary` 与上传守卫补 `X-APPLE-WEBAUTH-PCS-Photos` 校验（当前只校验 `PCS-CloudKit`）
- [ ] **Android 端：后台任务调度 (`WorkManager`)**
  - [ ] `PhotoBackupWorker` + `ForegroundService (dataSync)` 通知栏进度与退避重试
  - [ ] 监听用 `Constraints.Builder().addContentUriTrigger(uri, true)`，
        而不是内存里的 `ContentObserver`（进程后台被杀即失效）
  - [ ] 仅 Wi-Fi / 充电 / 电量充足约束；错误分类复用 `classifyCoreResponse()`

### Phase 4: 跨生态深度适配 (安卓 + 苹果痛点打磨)

- [x] **Motion Photo ↔ Live Photo 互转**
  - [x] **上传（安卓 → iCloud）**：检测 Motion Photo XMP 标签（GCamera 与 Container v2 多行格式），自动拆分静图与伴侣 MP4 视频，向 CloudKit 组装双资源 `CPLMaster` 与 `CPLAsset` 上传（详见 [ISS-044](CHANGELOG_ISSUES.md#iss-044-motion-photo--live-photo-自动拆分与双资源-cloudkit-备份上传)）
  - [x] **下载（iCloud → 安卓）**：`CPLMaster` 含 `resOriginalVidComplRes` 时同时下载图+视频，按 Google/三星 Motion Photo 规范插入 APP1 XMP (`GCamera:MotionPhoto="1"`) 拼接单文件，全量复制 EXIF 并将 Bitmap 物理旋转至正向（详见 [ISS-037](CHANGELOG_ISSUES.md#iss-037-实况照片单文件-motion-photo-打包与-exif-防旋转对齐)）
- [ ] **折叠屏与大屏自适应布局**
  - [ ] `WindowSizeClass` 适配：外屏单栏底部导航 / 内屏与平板 `NavigationRail` + 双栏详情

---

### Phase 5: iCloud Drive（云盘浏览与同步）⏳ 未开始

> **详细拆分见 [docs/TODO_DRIVE.md](TODO_DRIVE.md)（D0 ~ D8）。**

> [!IMPORTANT]
> **iCloud Drive 与 Photos 是两套完全不同的协议栈。**
> 只有 Cookie / Header / 会话握手可复用；
> 元数据模型（真实目录树 vs 时间线平铺）、增量同步（`changes/zone` + `changes/database` 两路）、
> 上传（`/upload/web` 分片 vs 单文件直传）、下载（`data_token` vs `cvws` 直链）
> **全部不能照搬**。

- [ ] **Phase 5.0: 协议侦察与探针**（必须最先，禁止读代码猜字段）
  - [ ] 探明 `webservices.drivews` 端点与字段字典 → 补充 [REFERENCE.md](REFERENCE.md) §5
  - [ ] 逐字段 diff「普通文件夹」与「iWork 文档包」
- [ ] **Phase 5.1: Go 协议底座**（`drive.go` + `Session.DrivewsURL` 回写 + 目录列举）
- [ ] **Phase 5.2: 数据层与目录浏览 UI**（`DriveDatabase` 独立库 + `feature/drive/` + 面包屑列表）
- [ ] **Phase 5.3: 增量同步与对账**（`changes/zone` + `changes/database`、显式 `walkCompleted`、可推导闸门）
- [ ] **Phase 5.4: 下载**（`download/by_id` → `ws/download`，直链刷新复用三层去重）
- [ ] **Phase 5.5: 上传**（`/upload/web` 分片，零内存流式，独立上传队列）
- [ ] **Phase 5.6: 目录操作与冲突处理**（本地优先 + 乐观更新 + 失败回滚）
- [ ] **Phase 5.7: 预览与分享**（图片/视频复用 Coil + ExoPlayer；PDF；iWork 缩略图）
- [ ] **Phase 5.8: 跨功能收尾**（登出四阶段清洗扩展到 Drive、设置项、文档归档）
- [ ] **前置重构**：`dataMutationMutex` 从 `PhotoRepository` 提取为中立设施（Drive 必须共用，不得另起一把）

---

> 参考资料、避坑指南与 Phase 1 详细过程记录已拆分至 [docs/](docs/) 目录，详见上方文档索引。
