# AGENTS.md - AI Agent 协助开发规范指南

本文档为参与 **iClouDroid** 项目协同开发的 AI Agent 提供架构导引、编码约定与编译调试规范。

> [!IMPORTANT]
> **新接手请先阅读第 5、6、7、9 节**（会话生命周期 / URL 生命周期 / 错误判定 / 验证纪律）。
> 这四节集中了本项目踩过的所有重大坑，违反任意一条都会导致难排查的诡异问题。

## 目录

| 章节 | 内容 |
| --- | --- |
| [1](#1-工程目录结构说明) | 工程目录结构 |
| [2](#2-编译与构建工作流) | 编译与构建工作流 |
| [3](#3-go-跨语言交互-gomobile-编写规范) | Go 跨语言交互 (gomobile) |
| [4](#4-icloud-协议开发红线) | iCloud 协议开发红线 |
| [5](#5-会话生命周期session-lifecycle) | 会话生命周期 |
| [6](#6-url-生命周期与刷新规范) | URL 生命周期与刷新 |
| [6.7](#67-系统云端媒体提供方cloudmediaprovider) | **系统云端媒体提供方（必读）** |
| [6.7.9](#679-drive-上传队列与-photos-上传完全不同) | **Drive 上传队列（必读）**，含上传大小上限的区域差异 |
| [7](#7-错误判定规范) | 错误判定规范 |
| [8](#8-日志与可观测性规范) | 日志与可观测性 |
| [9](#9-验证纪律verification-discipline) | 验证纪律 |
| [10](#10-代码结构导引android-层) | 代码结构导引 |
| [11](#11-问题归档与文档维护) | 问题归档与文档维护 |
| [12](#12-当前进度与下一步) | 当前进度与下一步 |

---

## 1. 工程目录结构说明

```
/Users/syh/iClouDroid/
├── TODO.md                 # 分阶段功能路线图
├── README.md               # 项目公开说明与快速上手
├── AGENTS.md               # AI Agent 指导规范 (本文件)
├── CHANGELOG_ISSUES.md     # 已修复问题案例库 (ISS-001 ~)
├── list.md                 # 待修复问题清单（含推荐方案）
├── scripts/
│   └── build_aar.sh        # Go gomobile 交叉编译构建脚本 (关键!)
├── docs/
│   ├── PITFALLS.md         # 工程避坑指南
│   ├── REFERENCE.md        # 参考开源项目源码对照
│   └── ARCHIVE_PHASE1.md   # Phase 1 过程存档
├── go-core/                # Go 核心协议层 (go mod init icloudcore)
│   ├── go.mod
│   ├── core.go             # gomobile 导出入口、会话摘要
│   ├── session.go          # Session、Cookie 解析与请求封装
│   ├── account.go          # 账户登录、路由表解析、会话自愈
│   ├── photos.go           # CloudKit 照片：zones/list、records/query|changes|lookup
│   └── *_test.go           # 单测（可用 httptest 模拟 CloudKit）
└── app/                    # Android 主工程 (Kotlin + Compose)
    ├── libs/
    │   └── icloudcore.aar  # 由 scripts/build_aar.sh 自动生成
    └── src/main/java/com/getup/iClouDroid/
        ├── MainActivity.kt
        ├── data/           # db / repository / coil / media / session
        └── ui/             # auth / photos
```

Android 层细分见第 10 节。

---

## 2. 编译与构建工作流

在修改 Go 代码或 Android 代码后，请严格遵照以下步骤：

1. **若修改了 `/go-core`**：
   **必须**优先运行 `../scripts/build_aar.sh`。不要直接手动调用裸 `gomobile bind` 命令，因为该脚本会自动配置 `JAVA_HOME` 和 `ANDROID_NDK_HOME`。
2. **若修改了 Android 代码**：
   可以使用 `gradle_build` 工具调用 `:app:assembleDebug` 或 `:app:compileDebugKotlin`。
3. **禁止在 Shell 中使用文本替换工具**：
   **切勿**使用 `sed`、`awk` 或 `echo` 修改代码文件，请始终使用 IDE 提供的内置文件写入/替换工具。

---

## 3. Go 跨语言交互 (gomobile) 编写规范

1. **类型限制**：
   `gomobile bind` 仅支持 Go 基本数据类型（`string`, `int64`, `bool`, `[]byte`）及简单 Struct。
2. **数据传递首选 JSON**：
   复杂数据结构（如账号信息、照片列表等）请在 Go 端序列化为 **JSON 字符串**，在 Kotlin 端解析。
3. **异常处理机制**：
   Go 函数返回 `(result, error)` 时，`gomobile` 会将其转化为 Java/Kotlin 中的 `throws Exception`。请在 Kotlin 协程体中总是使用 `try-catch` 包裹调用。
4. **大文件传输禁忌**：
   **绝对不要**用 `[]byte` 传递照片或视频内容，会触发 Android JNI OOM！必须在 Kotlin 传递文件路径，由 Go 直接读写本地文件流。
5. **禁止手写序列化格式（ISS-040 血泪教训）**：
   binary plist / protobuf / ISO-BMFF 这类格式**必须**用已有成熟库（本项目已有 `howett.net/plist`），
   禁止手写字节。**写完之后必须用外部解析器验证**（macOS `plutil -p`），
   **同库 round-trip 不够** —— 旧的手写 bplist 编码器在本进程里测也是「成功」的，
   而 `plutil` 直接拒解、Python `plistlib` 则静默返回垃圾结构。
   任何编码失败必须**省略该字段**，绝不写入可疑字节。

---

## 4. iCloud 协议开发红线

1. **请求头 (Headers) 强校验**：
   所有发往 Apple 接口的请求，必须携带有正确的 `User-Agent`，以及与节点匹配的 `Origin` 和 `Referer`：
   - 国区：`Origin: https://www.icloud.com.cn`，`Referer: https://www.icloud.com.cn/`
   - 国际区：`Origin: https://www.icloud.com`，`Referer: https://www.icloud.com/`
2. **三要素 Cookie**：
   任何 Session 的建立必须包含：
   - `X-APPLE-WEBAUTH-TOKEN`
   - `X-APPLE-WEBAUTH-HSA-TRUST`
   - `X-APPLE-WEBAUTH-PCS-CloudKit`
3. **Cookie 跨子域手动注入**：
   不要依赖 `cookiejar` 自动匹配。`golang.org/x/net/publicsuffix` 会把 `com.cn` 视为公共后缀，**拒绝**向父域下发 Cookie；且 CloudKit 节点 (`pXX-ckdatabasews.icloud.com.cn`) 与对象存储 (`cvws.icloud-content.com.cn`) 属不同子域。必须由 `Session.CookieMap` 在 `NewRequest` 中显式拼装 `Cookie` Header。
4. **下载直链的寿命由时间决定，与代际无关（已实测）**：
   `cvws.icloud-content.com[.cn]` 返回的 URL 实测寿命约 **15~18 分钟**（物理机实测：
   882s 仍 200，1065s 变 410），**不是** “固定的 1~2 小时”。

   > [!CAUTION]
   > **「会话重建导致 URL 失效」是已被证伪的假设。** 实测：gen=6 签发的 URL 在会话重建到
   > gen=7 之后仍然返回 `200`。CDN 签名绑定的是 record，不是签发它的会话。
   > 详见 [ISS-026](CHANGELOG_ISSUES.md#iss-026) 与
   > [url_lifetime_experiment.md](.artifacts/url_lifetime_experiment.md)。

   因此 URL 刷新只由**年龄**和**真实失败（403/410）**驱动，不靠代际。详见第 6 节。

5. **EXIF / GPS 元数据藏在 `mediaMetaDataEnc`（已实测）**：
   `CPLMaster` 的 `mediaMetaDataEnc` 是 **base64 包裹的 binary plist**（魔数 `bplist00`），
   解出后与 Apple `CGImageProperties` 同构，内含 `{TIFF}` / `{Exif}` / `{GPS}` / `{PNG}` 子字典。
   光圈、快门、ISO、焦距、镜头、拍摄时间、经纬度、海拔全部可得。
   完整字段字典见 [docs/REFERENCE.md](REFERENCE.md) 第 2A 节。

   > [!IMPORTANT]
   > **`{GPS}` 不是必然存在** —— 截图与关闭定位的照片都没有。实测 25 条中仅 8 条带 GPS。
   > 任何展示层必须按字段存在性条件渲染。只判断“有无位置”时用 `CPLAsset.locationEnc`，
   > 不必解码整个 plist。

6. **上传查重是两阶段：本地指纹索引 + 云端 `lookup` 兜底（Round 2 探针实测）**：
   先把两个“哈希”彻底分开 —— 它们长得像，用途完全不同：

   | 哈希 | 长度 | 来源 | 用途 |
   | --- | --- | --- | --- |
   | **SHA-256** | 32 字节 / 44 字符 Base64（`=` 结尾） | 客户端自己算 | 仅作 `/assets/upload` 申请上传令牌的**签名** |
   | **MMCS `fileChecksum`** | 21 字节 / 28 字符 Base64（`A` 开头，首字节 `0x01`） | 上传完成后由服务端返回 | **这才是 `resOriginalFingerprint`，也等于 `CPLMaster.recordName`（`masterId`）** |

   实测铁律：**`masterId == resOriginalFingerprint == receipt.singleFile.fileChecksum`**。
   所以 `/records/modify` 建 `CPLMaster` 时 `recordName` 必须填 `fileChecksum`，
   **不能填 `/assets/upload` 返回的那个 UUID**（它只是本次上传会话的票据）。

   查重因此有两条路：

   1. **最优**：在 Go 层实现本地 MMCS 21 字节指纹算法（流式读文件），拿结果直接查
      `photo_assets.masterId`，零网络；未命中再发一次 `/records/lookup`
      （`recordName = fingerprint`，`desiredKeys = ["recordName"]`）兜底。
   2. **退避**：暂不实现本地算法时，维护 `(filePath, fileSize, lastModified, sha256) → masterId`
      本地映射表。代价是识别不了“其他 Apple 设备已传过同一张原图”。

   结论：**前置查重不能指望云端“现成指纹字段”，必须先在本地建索引；
   云端只做幂等兜底。** 详见 [docs/REFERENCE.md](REFERENCE.md) §3A.4。

7. **`/records/query` 的 `resultsLimit` 必须 > 1**：
   传 `1` 会直接返回 `400 BAD_REQUEST: Result size limit must be greater than 1`。

8. **`desiredKeys` 是白名单，CloudKit 的删除是两段式（已实测）**：
   CloudKit **只返回 `desiredKeys` 里列出的字段**。`defaultDesiredKeys` 长期含 `isHidden` 却漏了
   `isDeleted`，导致“最近删除”的照片被当作正常照片入库（[ISS-030](CHANGELOG_ISSUES.md#iss-030)）。

   | 阶段 | `CPLAsset` | `/records/query` | `/records/changes` |
   | --- | --- | --- | --- |
   | 移入「最近删除」 | `isDeleted = 1`，记录**还在** | 被 `withoutHiddenOrDeleted` 视图过滤 | **无变更** |
   | 30 天后 expunge | 记录消失 | 不再返回 | 给出 tombstone |

   > [!CAUTION]
   > **不能靠增量同步发现“移入回收站”** —— 那不是 tombstone，`/records/changes` 什么都不返回。
   > 必须显式拉回收站索引，recordType 是 **`CPLAssetAndMasterDeletedByExpungedDate`**。
   > `CPLAssetDeletedByExpungedDate` 只是 `HyperionIndexCountLookup` 的计数 ID，拿它查询会
   > 得到 `404 Did not find record type`。
   >
   > 同理：回收站期内的 `CPLMaster`（含 `downloadURL`）仍会随 query 返回，
   > **绝不能把未配对的 master 伪造成一张照片**，否则回收站照片会“凭空”出现在画廊里。

> [!WARNING]
> **`InitSession` 必须把握手结果回写到 session 自身。** `ValidateSession()` 返回的
> `DSID` / `CKDatabaseURL` 只是返回值，如果不同步到 `Session` 结构体，任何**进程内**
> 调用 `photosBaseURL()` 都会拼出空 host（`unsupported protocol scheme ""`）。
> 此前未暴露，是因为 Kotlin 层自己读了返回值并存进加密存储。详见 [ISS-029](CHANGELOG_ISSUES.md#iss-029)。

9. **上传记录的字段类型与取值必须逐一对齐真实记录（ISS-040）**：

   | 规则 | 说明 |
   | --- | --- |
   | **`res*FileType` 存的是 UTI，不是 MIME** | `public.heic` / `public.jpeg` / `public.mpeg-4`。写 `image/heic` 会让**所有**资源在 Apple 端不可渲染，而我们自己的读取路径根本不碰这几个字段，所以 Android 端一直正常 |
   | **`/records/modify` 返回 200 不等于成功** | 必须解析 `records[].serverErrorCode`（见 `extractRecordErrors`） |
   | **上传前必须幂等查重** | `CPLMaster.recordName` **就是**内容指纹（MMCS `fileChecksum`），`masterRecordExists()` 一次 lookup 即可判定。同一文件传两次会得到**同一个 master 但新的 `CPLAsset`** —— Apple 按 master 归并所以只显示一条，我们的网格按 asset recordName 归并 → **只有 App 内重复** |
   | **视频的 `mediaMetaDataEnc` 是 `AVAssetProxy`** | `mediaMetaDataType = "AVAssetProxy"`，plist 为 `{moop: ftyp+moov 字节, mpvr: 1, name: "<CPLAsset id>.MP4"}`。缺它则**时长显示 0:00**（不是缺派生图导致的） |
   | **视频必须有 JPEG 派生图** | 缺 `resJPEGThumbRes` / `resJPEGMedRes` 时 Apple 端无 poster frame，本 App 网格也会走 Coil error 槽（那格灰底「iCloud」字样）|
   | **视频时长在 `CPLAsset.duration`** | 不在 master。写错位置则显示 `0:00`，即使 `moop` 里的 moov 已含真实时长 |
   | **视频「相机信息」来自 QuickTime `moov/meta`** | `com.apple.quicktime.make` / `.model` / `.software` / `.creationDate` / `location.ISO6709`。不是 EXIF。安卓 MP4 里一个都没有 → 信息面板显示「无相机信息」 |
   | **`uploadFileToUrl` 会关闭传入的 `*os.File`** | 它作为 `http.NewRequest` 的 body 被 net/http 接管并 Close。**任何后续 `ReadAt` 都会失败**，且失败只表现为「字段缺失」。需在流式上传**之前**抽完 moov。单测里不会暴露（没有上传步骤），见 `TestFileClosedAfterStreamingUpload` |

   > [!CAUTION]
   > **GPS 需要 `ACCESS_MEDIA_LOCATION` 运行时权限（ISS-041）。照片和视频都需要，无一例外。**
   > 该权限在 Manifest 里声明了但**从未在运行时申请**，导致 `granted=false`。
   >
   > | 对象 | 来源 | 授权 | 不授权 |
   > | --- | --- | --- | --- |
   > | 照片 | `ExifInterface.latLong` | 9/60 有 GPS | **0/60** |
   > | 视频 | `moov/udta/©xyz`（atom 原始字节） | `+35.9553+120.1715/` | **atom 被整个移除** |
   > | 视频 | MediaStore `LATITUDE/LONGITUDE` | 常为 `null` | `null` |
   >
   > 实测结论：MediaProvider 是**删掉** `©xyz` atom（`findBox` 直接返回 nil），
   > 不是置零。所以**不存在“零权限读视频 GPS”的路径** ——
   > 之前曾误以为容器原子能绕过，已证伪。

   > [!WARNING]
   > **`0,0` 必须当作「无坐标」。** 平台抹除位置时会**置零**而非删标签。
   > 直写会让每张备份照片都钉在几内亚湾。Kotlin 与 Go 两侧都已防护。

   > [!CAUTION]
   > **排查此类问题禁止靠读代码猜字段。** `CKField.AsInt64()` / `AsResource()` 是**宽松解析**，
   > 值存错了也照样能读出来 —— 这正是 ISS-040 活到线上的原因。
   > 必须用 **`desiredKeys` 全开**的 `/records/lookup` 拿服务端真相（含每个字段的 `type`），
   > 并与一条**苹果自己创建的、同格式的**记录逐字段 diff。
   > 没有现成工具：临时在 Go 里加一个不带 `desiredKeys` 的 lookup 函数、配一个
   > `androidTest` 把响应写进 `cacheDir`，用完**立即删除**（`/records/lookup` 的
   > `desiredKeys` 是白名单，带了它就看不到全部字段）。

   > [!CAUTION]
   > **位置要写两个字段，缺一不可（ISS-043）。**
   >
   > | 字段 | 内容 | 不写会怎样 |
   > | --- | --- | --- |
   > | `CPLMaster.mediaMetaDataEnc` 的 `{GPS}` | EXIF 视角的坐标 | 自己的 App 信息卡也没坐标 |
   > | **`CPLAsset.locationEnc`** | 序列化的 `CLLocation`（binary plist）| **网页 / Mac / iOS 无地图**，自己的 App 却正常 |
   >
   > `locationEnc` 的 8 个键：`lat` / `lon` / `alt` / `course` / `speed` /
   > `horzAcc` / `vertAcc` / `timestamp`。**`timestamp` 必须是 plist `<date>`，
   > 不是字符串** —— 类型错会让接收端解出错误的类。见 `location_enc.go`。
   >
   > 该不对称（自写自读全对、只有网页不显示）正是它长期未被发现的原因：
   > **“我们的客户端能读”不是验证，拿原生记录逐字段 diff 才是。**

---

## 5. 会话生命周期（Session Lifecycle）

> 踩坑记录：ISS-012（冷启动丢会话）、ISS-016（generation 洪泛）。

### 5.1 Go 侧 `currentSession` 是进程级全局指针

App 进程冷启动后，Go 层的 `currentSession` 必为 `nil`。**切勿**把本地缓存的 `dsid` / `ckDatabaseUrl` 当作「会话已就绪」的依据 —— 它们只是服务端路由快照，不代表 `*Session` 指针存在。

所有需要 Go 会话的操作，必须先经过 [PhotoRepository.ensureGoSession()](file:///Users/syh/iClouDroid/app/src/main/java/com/getup/iClouDroid/data/repository/PhotoRepository.kt) 守卫：

```kotlin
// 判定依据：Go 层自报的 Session 摘要，而非本地缓存
val summaryStr = Icloudcore.getSessionStateSummary()
val hasActiveSession = summaryJson.optBoolean("hasAuthToken", false) &&
    summaryJson.optString("ckDatabaseURL", "").isNotBlank()
```

### 5.2 会话重建是「昂贵操作」

一次重建 = 一次 `/validate` 网络往返（1~2s）+（仅在 `force = true` 恢复路径下）一次 generation 递增。
注：已签发 URL **不会**因会话重建作废（详见 §4.4 与 [ISS-026](CHANGELOG_ISSUES.md#iss-026)），
但频繁重建仍会严重拖慢请求并增加限流风险。

> [!WARNING]
> 任何触发重建的路径都必须极度保守。宁可提示用户「网络错误，请重试」，也不要贸然 `ensureGoSession(force = true)`。

### 5.3 `bumpSessionGeneration()` 只能调一次

**只能在 `initSession` 真正成功后**递增。绝不可以在「会话已就绪」的快速路径上调用 —— 该路径每次 API 请求都会经过，会让 generation 每几十毫秒 +1，导致每一格缩略图都变成「代际过期」并各发一次 `/records/lookup`（即 URL 刷新洪泛）。

> [!NOTE]
> **已修复（[ISS-026](CHANGELOG_ISSUES.md#iss-026)）**：此前 `applyRefreshedCookies()` 用**字符串相等**
> 判断凭证是否轮转，而 Go 的 `BuildCookieHeader()` 遍历 `map` 拼接 Cookie（顺序随机），
> 导致每次冷启动都 bump 一次 generation。现已 Go 侧按 key 排序 + Kotlin 侧改集合比较。
>
> 更重要的是：**代际 bump 不再由 Cookie 变化触发。** 实测发现苹果每次 `/validate` 都会轮换
> `X-APPLE-WEBAUTH-TOKEN` / `X-APPLE-WEBAUTH-VALIDATE`，而这两个令牌与已签发 URL 的有效性无关。
> 现在只有 `force = true`（401/421 恢复路径）才会 bump。

### 5.4 会话代际机制

| 角色 | 字段 | 说明 |
| --- | --- | --- |
| `SessionManager` | `session_generation` | 持久化的单调递增计数器 |
| `PhotoAssetEntity` | `sessionGeneration` | 记录入库时打戳 |

判定：代际已降级为**辅助信号**，仅当「代际不符 **且** URL 年龄已过半（> 6 分钟）」时才提前刷新
（见 §6.1.1）。刷新成功后仍必须把当前 generation 回写到该行。

> [!CAUTION]
> 刷新成功后**必须把当前 generation 回写到该行**，否则它永远停留在 stale 状态，每次加载都重复刷新。

### 5.5 换区 / 换账号必须清空数据层

会话层清理（Cookie / WebStorage / `EncryptedSharedPreferences`）**远不够**。
Room 照片表与 Coil 缓存都以 `recordName` 为主键，而这些 ID 属于特定账号：

| 数据 | 位置 | 是否账号相关 |
| --- | --- | --- |
| `photo_assets` | `PhotoDatabase` | ✅ CloudKit 记录 ID |
| `sync_state` | `PhotoDatabase` | ✅ `syncToken` / `continuationMarker` |
| 图片字节 | Coil `DiskLruCache` | ✅ 来自旧账号的 `cvws` 直链 |

新账号登录后会 `bumpSessionGeneration()`，旧行全部 stale → 首屏批量刷新，
但用的是**新账号 Cookie** 去查**旧账号 recordName**，必然失败，表现为
「列表有内容但图片全部加载失败」。

**当前状态**（已修复，详见 [ISS-025](CHANGELOG_ISSUES.md#iss-025)）：

`logout()` 已改为完整四阶段清洗，顺序固定不可调换：

1. **阻断** `PhotoRepository.beginWipe()` —— 置 `isWiping = true`，所有网络入口
   （`checkZoneReady` / `fetchInitialPage` / `fetchNextPage` / `syncIncremental` /
   `refreshUrlsForMaster(s)`）与 `MonthScanEngine`（全盘扫描）/ `syncIncremental`（增量同步）/
   `UploadWorker`（Phase 3 上传）立即拒绝工作
2. **数据层** `wipeAllData()` —— `sync_state` + `photo_assets` 全清，并清理刷新去重缓存
3. **图片层** `ICloudImageLoaderFactory.clearCaches()` —— Coil 内存 + 磁盘
4. **会话层** `clearWebViewCookies()`（桥接回调后 `flush()`）+ `sessionManager.clearSession()`
   最后 `endWipe()` 释放守卫。

修改 `logout()` 时注意：

1. **先阻断再清库** —— 否则正在飞行中的全盘扫描 / 增量同步 / 上传任务会 `upsertAll()`
   把已删的行写回。
2. **不能以 `isWiping` 之外的任何方式抑制同步** —— 用同步布尔哨兵位，
   不用 Flow/State（异步会竞态，见 [docs/PITFALLS.md](PITFALLS.md) §7）。
3. **顺序：阻断 → 数据层 → Coil → 会话层** —— 先清 Cookie 会让中途的自动刷新
   拿空 Cookie 打 Apple 接口；先清数据层再阻断则会被写回。
4. **`removeAllCookies` 必须等回调** —— 传 `null` 只是放弃回调，并不代表已同步完成，
   紧接着的 `flush()` 可能把删除前的 jar 落盘。
5. **`AuthViewModel.isLoggingOut` 必须从 `logout()` 第一行就开始生效** ——
   `ICloudWebView` 的 Cookie 探针会在 `removeAllCookies` 完成前读到旧 Cookie
   并回调 `onCookiesExtracted()`，把刚销毁的会话重新持久化。
   `AuthUiState` **挡不住**这个竞态（`logout()` 第一时间就置为 `NotLoggedIn`，
   反而放行了回调），必须用 `@Volatile` 布尔位，并在清理完成后再保留
   `COOKIE_CAPTURE_GRACE_MS`（800ms）等登录页彻底重载。见
   [ISS-025](CHANGELOG_ISSUES.md#iss-025) 的“竞态”小节。

> [!CAUTION]
> **新增任何「退出登录时需要清理的状态」，同时必须检查它能不能被异步回调写回。**
> 本项目已验证的写回路径有两条：`MonthScanEngine` / `syncIncremental` / `UploadWorker` →
> `upsertAll()`（历史案例中的执行者原为已删除的 `PhotoRemoteMediator`，详见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)），
> 以及 `ICloudWebView` 的 Cookie 探针 → `onCookiesExtracted()` → `saveSession()`。

---

## 6. URL 生命周期与刷新规范

### 6.1 所有消耗 URL 的路径必须统一走刷新

| 路径 | 事前检查 | 失败后重试 | 重试上限 |
| --- | --- | --- | --- |
| Coil 缩略图 | **年龄 12min**（+ 代际仅作提示） | `ErrorResult` → 刷新重请 | 1 |
| 图片详情 | — | `onError` → 刷新 → `reloadToken++` | 2 |
| 视频播放 | — | 重建 `ExoPlayer` + `seekTo(lastPosition)` | 2 |
| 相册下载 | 年龄 | HTTP 403/410 捕获后换链 | 1 |

### 6.1.1 阈值必须来自实测，不要猜

| 常量 | 值 | 依据 |
| --- | --- | --- |
| `URL_MAX_AGE_MS` | **12 分钟** | 实测 882s(14.7m) 仍 206，1065s(17.8m) 变 410 |

> [!CAUTION]
> **不要把这个值调回 45 分钟。** 旧值基于「1~2 小时」的错误假设，留下约 30 分钟的盲区：
> URL 早已 410，系统却仍以为有效，只能靠失败重试兜底。
> 若苹果将来改变寿命，用 [实测脚本](.artifacts/url_lifetime_experiment.md) 重新标定，别拍脑袋。

**代际（`sessionGeneration`）的定位已降级**：它不再是刷新触发器。
实测已证 URL 跨会话重建仍有效，因此在拦截器里只有「代际不符 **且** 年龄过半」才提前刷新 ——
这是为换账号场景（[P2-018](CHANGELOG_ISSUES.md#iss-025)）留的加速信号，不是常规路径。

### 6.2 刷新必须走 `PhotoRepository`，禁止直调 Go

`refreshUrlsForMaster(masterId)` 内建三层去重：

1. 同一 masterId 的 in-flight 请求合并（`Deferred` 共享）
2. 5 分钟窗口内成功刷过的直接读 DB
3. 会话刚重建时加 20~120ms 随机抖动，摊平突发

批量场景用 `refreshUrlsForMasters(List<String>)`，一次 `/records/lookup` 搞定多个。

> [!WARNING]
> 若在拦截器 / UI 里绕过 `PhotoRepository` 直调 `Icloudcore.refreshAssetUrls()`，网格 60+ item 同时失败时就会瞬间发出几十个 `/records/lookup`，极易触发 Apple 限流。

### 6.3 Coil 缓存 Key 必须绑定 `recordName`

内存与磁盘缓存 Key 使用 `icloud_{recordName}_{quality}`，**绝不能**绑定会变化的 `downloadURL`，否则签名一换缓存全废。

### 6.4 滚动锚点必须是「月份 key」，不能是全局 index

画廊按**月分组**展示，滚动锚点是 `monthKey`（`yyyyMM`），不是列表序号。

| 旧实现（已删除） | 现状 |
| --- | --- |
| `RemoteMediator` + Paging 3，锚点 = `firstVisibleItemIndex` | `LazyColumn` + 行展开，锚点 = `monthKey` |
| 每次 APPEND 必 invalidate，itemCount 塔陷 → 跳顶 | 无 invalidate，锚点不随追加漂移 |

> [!CAUTION]
> **不要重新引入 Paging 3 作为分页源。** `RemoteMediator.load()` 返回 `Success` 后，
> Paging 3 会**无条件** invalidate `PagingSource`，重载窗口 = `initialLoadSize`，
> 超出窗口的滚动位置被 clamp。这一点已实测确认（60→60、360→360、1200→1200），
> **无参数可解**。详见 [ISS-021](CHANGELOG_ISSUES.md#iss-021) 与
> [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)。

### 6.5 惰性布局不得嵌套

画廊原本每个月是一个嵌套的 `LazyVerticalGrid`。嵌套惰性布局必须给定固定高度，因而内层拥有自己的
测量循环；外层快速滚动时两者失去同步，内层只量出行首一行、其余折叠，表现为「月份标题出现、内容不加载」。

**约定**：`LazyColumn` 内不得嵌套 `LazyVerticalGrid` / `LazyHorizontalGrid`。
需要网格时按行发出 item，每行内用 `Row` + `weight(1f)` 排布固定数量的格子（详见 `MonthRow`）。

---

## 6.6 月分组数据层约定

| 概念 | 约定 |
| --- | --- |
| 分桶键 | `photo_assets.yearMonth`（`yyyyMM`，**设备本地时区**） |
| 写入时机 | `parsePhotoItems` 内即时计算，不留 `0` |
| `0` 的含义 | 「未分桶」，月查询选不到 → 表现为「有数据但看不见」 |
| 索引表 | `photo_month_index`（派生数据，可随时重建） |
| 封面 | `coverAssetId` —— 该月 `assetDate DESC` 首项，**只存 id 不存 URL**（签名 15 分钟就死） |
| 封面开关 | `AppSettings.monthCoverEnabled` 同时管住**下载**与**渲染**，不是纯 UI 开关 |
| 扫描状态 | `scan_state`（含游标，断点续扫） |
| `isComplete` | **派生值**，每次从 `scan_state` 读取，不得由调用方传入 |

> [!WARNING]
> **判断「是否需要初始化扫描」不能看行数。** 升级安装有行但未分桶，
> `count == 0` 为假、初始化被跳过。应判断「是否存在未分桶行」（`countUnbucketed()`）。

> [!CAUTION]
> **月份轻封面（`coverAssetId`）曾因多处数据与渲染断链而空白 —— 已修复，见
> [ISS-038](CHANGELOG_ISSUES.md#iss-038-年月跳转小窗月份轻封面缩略图空白四层断链修复)。**
> 以下是排查中确认的**四条硬规则**，改任何与封面/Coil 相关的代码前必须先读：
>
> **① 必须显式传 `imageLoader`，否则用的是系统默认 loader。**
> 本项目的 loader 带 `ICloudPhotoKeyer` + `ICloudPhotoInterceptor` + `ImageDecoderDecoder`，
> 且带磁盘缓存。`AsyncImage(model = ...)` 不传 `imageLoader` 参数时会回退到 `Coil.imageLoader(context)`,
> 那个单例**完全不知道** `ICloudImageModel` 是什么：没有 Keyer → 缓存 key 变成对象哈希，
> 没有 Fetcher → 直接失败，而且**一行日志都不会打**（因为拦截器根本没被调用）。
> 正确写法抄 [PhotoGridItem.kt](file:///Users/syh/iClouDroid/app/src/main/java/com/getup/iClouDroid/feature/photos/ui/PhotoGridItem.kt)：
> `imageLoader = ICloudImageLoaderFactory.get(context, rememberAppContainer())`。
>
> **② 手写 `ImageLoader.Builder(context).build()` 默认没有磁盘缓存。**
> Coil 只在通过 `Coil.imageLoader(context)` 单例构建时才自动装磁盘缓存。实测：没有显式
> `.diskCache { }` 时，预热日志写 `108/108 cached`，而 `cache/image_cache` 新增 **0 个文件**；
> 补上之后同样一次预热从 352K 涨到 6.4M。这条同时解释了 `clearCaches()` 为何曾是空操作
> （退出登录后旧账号缩略图实际没被清掉）。
>
> **③ `MonthScanEngine.refreshMonthIndex` 必须保留 `coverAssetId`。**
> 它每批都 `upsert(PhotoMonthIndexEntity(...))` 且没写 `coverAssetId`，等于每次扫描/冷启动回读
> 都把已填好的封面重置为 `null`。任何 `photo_month_index` 的 upsert 都必须显式带上该列。
>
> **④ DAO 必须查 `a.masterId`，并用 `COALESCE` 兜底封面。**
> `MonthCoverRow` 少了 `coverMasterId`，`MonthIndexBar` 就只能写 `masterId = ""`，
> 拦截器 `refreshUrlsForMaster("")` 必然失败 —— 而 `thumbUrl` 只是瞬时快照（签名 ~15min 就失效），
> 所以「有封面资产但 URL 为空」必须仍能画。正确形态：
> `COALESCE(m.coverAssetId, (SELECT id FROM photo_assets WHERE isHidden = 0 AND yearMonth = m.yearMonth ORDER BY assetDate DESC, id ASC LIMIT 1)) AS coverAssetId`
> + `a.masterId AS coverMasterId`，并由 `MonthIndexBar` 把 `masterId = cell.coverMasterId` 透传下去。

---

## 6.7 系统云端媒体提供方（CloudMediaProvider）

`provider/ICloudMediaProvider.kt` 把已同步的照片暴露给系统照片选择器。
这是整个项目里**最容易写出“代码全对但界面空白”**的模块，以下每条都是实测踩过的。

### 6.7.1 白名单：系统默认不认我们的应用

云端媒体提供方属于 **Google 的 OEM 试点项目**，受 `DeviceConfig` 白名单保护：

```
mediaprovider / storage_native_boot → allowed_cloud_providers
```

未入白名单的应用**不会**出现在系统「云端媒体设置」里——即使 Manifest 完全正确、
`get_cloud_provider` 也返回了我们的 authority。

**激活方式**（两者等价，后者可应用内一键完成）：

| 途径 | 要求 |
| --- | --- |
| ADB / Shizuku | 持 `android.uid.shell`（UID 2000）→ `ShizukuMediaHelper` |
| 纯应用内 | **不可行**。`MANAGE_CLOUD_MEDIA_PROVIDERS` 是 `signatureOrSystem` |

### 6.7.2 游标契约（缺任一列都可能静默失效）

`onQueryMedia` 必须包含以下列，且**行级 `SYNC_GENERATION` 必须与
`onGetMediaCollectionInfo` 的 `LAST_MEDIA_SYNC_GENERATION` 完全相等**，否则系统丢弃该行：

```
ID, DATE_TAKEN_MILLIS, SYNC_GENERATION, MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION,
SIZE_BYTES, DURATION_MILLIS, WIDTH, HEIGHT, ORIENTATION, MEDIA_STORE_URI
```

> [!CAUTION]
> **`MEDIA_STORE_URI` 缺失的最隐蔽后果**：格子会渲染出来，但系统**根本不发 `onOpenPreview`**。
> 日志里表现为“某些图一次请求都没有”，极难归因。云端条目填 `null` 即可（不能省略该列）。

**必须实现分页**（`EXTRA_PAGE_SIZE` / `EXTRA_PAGE_TOKEN`）。一次性返回全库会让 sync worker
中途放弃，表现为“只有首屏可用”。

### 6.7.3 MIME 既决定分类、也决定解码器（两者对视频冲突）

`MIME_TYPE` 在契约里承担两件事：**条目分类**（照片/视频）与 **Glide 解码器选择**。
对视频二者冲突：

| 报什么 | 分类 | 格子 |
| --- | --- | --- |
| `video/mp4` | ✅ 正确 | ❌ `VideoDecoder` 用 `MediaMetadataRetriever` 去解我们返回的 JPEG，报 `setDataSource failed: status = 0xFFFFFFEA`（实测 2540 次）|
| `image/jpeg` | ❌ 变成照片 | ✅ 能解 |

**两个都不能选。** 正确做法是保持 `MIME_TYPE` 为真实类型，并让 `onOpenPreview`
对视频行真的返回**视频容器**（详见 §6.7.5d）。

> [!WARNING]
> 本项目 **曾经选了 `image/jpeg` 来绕过解码失败**，结果就是“视频错误地显示为图片”。
> 如果你的改动又想往这个方向走，先回去读 §6.7.5d。

### 6.7.4 元数据变更必须 bump `SCHEMA_VERSION`

系统按 `LAST_MEDIA_SYNC_GENERATION` 缓存行，值不变就**不重新查询**。
改了任何列的取值规则（如上面的 MIME 修正），已安装设备仍会继续用旧行。

```kotlin
val generation = sessionGeneration * SCHEMA_VERSION   // 7 → 14
```

`SCHEMA_VERSION` 必须在 `onQueryMedia` 列值变化时**手工 +1**，并在注释里记历史。

### 6.7.5a 分页必须是 keyset，绝不能是 OFFSET（ISS-035）

> [!CAUTION]
> **`LIMIT n OFFSET m` 在云端媒体提供方里是错的。**
>
> `photo_assets` 在 picker 打开期间**不是只读的**：增量同步会 `upsertAll` 新照片，
> 月扫描与对账会 `deleteByIds`。OFFSET 锚定的是**行号**，任意一行增删都会让后面所有页
> 整体漂移一位 —— 一行被**跳过**（用户看到“少了图”），一行被**重复**（系统按 `ID` 去重丢弃）。
>
> 漂移会随页数累加，所以它**恰好只在上滑进入早期年份时显形**（那是最后几十页）。
> 症状组合极具误导性：宫格里有格子、点预览正常、下载正常，就是缩略图空白。

**正确做法**：token 编码 `(assetDate, id)`，查询用 `WHERE (assetDate < :d OR (assetDate = :d AND id > :i))`。

| 关键点 | 说明 |
| --- | --- |
| token 格式 | `"<assetDate>:<id>"`（不是 JSON，而是为了与旧的纯数字 OFFSET token 区分）|
| 旧 token 兼容 | 纯数字 token 只可能来自上一版，只服务一页且绝不再发；否则系统持久化的 token 会解析失败 |
| 排序键必须全序 | `ORDER BY assetDate DESC, id ASC` —— 大量照片共享同一秒，缺 `id` 决胜会让页边界不确定 |
| 索引 | 仍命中 `(isHidden, assetDate, id)` |
| 末页判定 | `page.size == pageSize` 才发 token。**`page.size < pageSize` 不等于结束**，最后一整页仍会带 token，需要容忍一次空页 |

实现见 `PhotoDao.getMediaPageFirst` / `getMediaPageAfter`，回归测试 `MediaPageKeysetTest`。

---

### 6.7.5 预览请求是一次性的，不能“先返回失败等重试”

实测结论（与常见假设相反）：

| 事实 | 后果 |
| --- | --- |
| PhotoPicker 对宫格格子**只请求一次** | 返回 `FileNotFoundException` = 永久空白 |
| `CancellationSignal` 在 ~250~400ms 就触发 | 但取消 **≠** 应丢弃已下数据 |
| 冷缩略图需要 400~1200ms（换链 + CDN） | 单一超时必然漏图 |

**正确做法：**
1. 取消时**不删**已下字节，后台继续下完落盘
2. **两级等待**：快档 1.2s、慢档 4s，超时后**再查一次磁盘**（`renameTo` 是下载线程最后一个动作）
3. 命中 Coil 磁盘缓存（key `icloud_{recordName}_thumb`）直接秒回
4. `onQueryMedia` 返回后**批量预热 URL**（消除每次首请求的 `/records/lookup`）

### 6.7.5b `length() > 0` 不是“这是个图”，必须验魔数

> [!CAUTION]
> **`200` 响应不等于图片字节。** `cvws` 在签名失效时会在 **200 状态下**返回一小段 HTML/XML 错误正文，
> `FileOutputStream` 会照写不误。一旦它以 `cmp_previews/<mediaId>.thumb` 落盘，
> `length() > 0` 会让它**永久命中**，之后每次 `onOpenPreview` 都把这个垃圾交给 Glide，
> 而下载路径再也不会被走到——因为文件永远“非空”。

规则：

| 位置 | 行为 |
| --- | --- |
| `isPlausiblePreview()` | 仅查前 12 字节容器魔数（JPEG/PNG/GIF/WebP/`ftyp`）+ 最小尺寸（512B），**不解码** |
| 缓存命中 | 校验失败 → `delete()` → 走重新下载，而不是直接返回 |
| `downloadToFile(requireImage = true)` | 预览必输：`.tmp` 落盘后先验魔数，不合格则**不 `renameTo`** |
| `onOpenMedia` | 传 `requireImage = false`，因为全图可能是 mp4/mov |

**为什么只查魔数不查尺寸**：这段代码跑在 binder 路径上，对每个冷格子做 `BitmapFactory` 解码
比它保护的下载本身更贵。魔数已经足以捕获“截断”与“CDN 错误正文”两种真实故障。

### 6.7.5c 视频缩略图永远不能降级到 `medUrl` / `originalUrl`

降级链 `thumbUrl ?: medUrl ?: originalUrl` 对**照片**安全（三张同题材不同尺寸），
对**视频**是错的：`medUrl` / `originalUrl` 就是 `mp4`/`mov` 字节本身。

把它当成 `image/jpeg` 交给 Glide = 必然空白，且**这次请求不会重试**。
实测 `/records/lookup` 偶尔会缺少 `thumbUrl`，而早期年份视频密度高
（每 1000 条含 48~336 个），所以这个缺陷**也只在上滑到早期年份时才密集暴露**。

**规则**：`mediaType in {VIDEO, LIVE_PHOTO}` → 只接受 `thumbUrl`，为空就返回 null
（宁可一次可归因的 MISS，不要一个永远解不开的“成功”文件）。
回归测试：`VideoThumbUrlTest`。

### 6.7.5d MIME 描述“**它是什么**”，不是“你返回的字节”

> [!CAUTION]
> **本节曾经写反过，以下是修正后的结论。**
> 早期版本的本文与 ISS-035 桹障七都说“视频报 `image/jpeg`”，理由是 `thumbUrl` 下到的是 JPEG。
> 那确实让格子能显示，但**代价是视频被当成了照片**：没有时长角标、没有视频分类，
> 调用方拿到的是 image 类型。用户看到的就是“视频错误地显示为图片”。

`MIME_TYPE` 在契约里**承担两件事**：

| 用途 | 看什么 |
| --- | --- |
| 条目**分类**（照片/视频、时长角标、筛选）| `MIME_TYPE` |
| 格子**解码器**选择 | `MIME_TYPE` |

对照片两者一致；对视频它们是冲突的（这是桹障七的根源）。**不能靠报 image 来绕过。**

**正确做法：在契约指定的位置拆开。**

| 列/方法 | 职责 |
| --- | --- |
| `MIME_TYPE` | 媒体**真实类型**：视频报 `video/mp4` / `video/quicktime` / `video/x-m4v` |
| `onOpenPreview` | 履行契约另一半（“thumbnail 应与 MIME_TYPE 一致”）—— 视频行**返回真实视频容器**，让 `VideoDecoder` 能抓帧 |

实现要点（`ICloudMediaProvider`）：

1. `onOpenPreview` 先查 `isVideoAsset(mediaId)`（主键索引，相对下载很便宜）
2. 视频 → 直接走 `getOrDownloadMediaFile`（与 `onOpenMedia` 同一份字节）
3. 取不到 → 才回退到 JPEG 缩略图（宁可画个图，不要空格）
4. 图片 → 维持原路径

`.heic` 仍报 `image/jpeg`：它的 `thumbUrl` 是服务端转码 JPEG，报 `image/heic` 会让
`BitmapDecoder` 去 JPEG 里找 HEIF 头——同类错误。但照片无论报哪个都是照片，所以没有分类代价。

回归测试：`MediaMimeTypeTest`（含一条“视频永不解析为 image”的穷尽护栏）。

### 6.7.5e `STANDARD_MIME_TYPE_EXTENSION` 救不了这个问题

它只有四个值：`NONE` / `GIF` / `MOTION_PHOTO` / `ANIMATED_WEBP`。
**没有“视频”这个值**，所以它无法用来把视频标记出来，别指望用它绕过上面的拆分。

### 6.7.6 排查时必须看系统进程的日志

自己的 provider 日志会显示 `openPreview OK bytes=83966 elapsed=1ms`——**完全正常**，
而屏幕上依旧空白。真正的失败在 PhotoPicker 进程里：

```bash
adb logcat | grep -E "Glide|VideoDecoder|setDataSource|Failed to find any load path"
```

其余可用的诊断手段：

| 手段 | 命令 |
| --- | --- |
| 当前激活的云端源 | `content call --uri content://media/ --method get_cloud_provider` |
| 白名单是否生效 | `dumpsys activity provider .../MediaProvider \| grep allowedCloudProviderPackages` |
| 系统缓存的代数 | `dumpsys ... \| grep cachedCloudMediaCollectionInfo` |
| 预览文件是否落盘 | `run-as com.getup.iClouDroid ls -la cache/cmp_previews` |
| 文件格式 | `run-as ... cat xxx.thumb \| head -c 12 \| od -An -tx1`（`ffd8ffe0` = JPEG）|

自己的日志侧也加了两个可归因信号：

| tag 行 | 含义 |
| --- | --- |
| `preview cached file is not an image ... head=<hex>` | 缓存里是坏字节，已丢弃重下 |
| `resolveThumbUrl: video has no thumbUrl ...` | 视频缺缩略图，**拒绝**回退到视频字节 |
| `openPreview extras media=... {...}` | 系统传了什么 extras（DEBUG，值截断 48 字符）|

> [!WARNING]
> **仍有已知残留**：快速向上滑入早期年份时，个别宫格仍空白。
> 已修复三处确定性缺陷（keyset 分页 / 缓存魔数校验 / 视频降级链），
> 详见 [ISS-035](CHANGELOG_ISSUES.md#iss-035photopicker-缩略图空白多层柹障式修复与已知残留)。

### 6.7.9 Drive 上传队列（与 Photos 上传完全不同）

> 踩坑记录：[ISS-049](CHANGELOG_ISSUES.md#iss-049-drive-上传卡在传输中数分钟哈希阶段零进度上报) /
> [ISS-050](CHANGELOG_ISSUES.md#iss-050-drive-上传双引擎并跑同一任务重复上传与等待重启循环) /
> [ISS-051](CHANGELOG_ISSUES.md#iss-051-drive-上传通知卡在-18-后跳到-100通知更新被系统合并)。

四条硬规则，改 Drive 上传相关代码前必读：

**① 只能有一个 drain 引擎。** `enqueueUpload()` 只入队，**不得**在里面启动处理器。
唯一入口是 `DriveUploadWorker`（它能在进程被杀后存活）。进程内协程与 WorkManager
曾同时领取同一任务并各自上传一遍。两者共用 `DriveRepository.uploadDrainMutex`。

> [!CAUTION]
> **`dataMutationLock` 救不了这个竞态。** 它在 `executeUpload` **内部**才获取，
> 那时两个调用方已经各自决定要上传了。需要的是在**进入 drain 之前**的互斥。

**② `getNextPending()` 绝不得重置 `UPLOADING`。** 它每轮都被调用，一旦内部执行
`resetUploadingToQueued()`，就要把刚领的任务又改回 QUEUED，循环永远前进不了、
同一文件无限重传。孤儿恢复是**每次 drain 开始前一次**的事。

**③ 长上传必须前台服务 + 有限重试。** 300 MB 传输要几分钟，普通后台 Worker
随时被系统回收；`REPLACE` 会直接 kill 正在传的 Worker；无限 `retry()` 会把
已取消的任务反复拉起、从 0 重传。用 `APPEND_OR_REPLACE` +
`FOREGROUND_SERVICE_TYPE_DATA_SYNC` + `runAttemptCount < 3`。

**④ 通知进度要控频且只在值变时发。** Room `Flow` + 250ms 写库 = 高频 `notify()`，
Android 会合并掉中间帧，表现为“卡在 18% 然后跳 100%”。用快照轮询 + 500ms +
percent/text 不变则不发。**UI 横幅可以继续用 Flow**（Compose 状态没有合并问题）。

另：Go 侧进度必须带 `Phase`（`HASH`/`UPLOAD`/`COMMIT`）。Drive 要对全文件做 SHA-256，
这段窗口没有网络流量，不过报就会表现为“卡在传输中数分钟”。

**⑤ 上传上限是“账号区域”属性，不得硬编码成全局限制。**

同一个 1.96 GB 文件、**逐字节相同**的请求（`/upload/web` → `singleFileUpload` 单次 POST）：

| 账号区域 | `docws` 主机 | 结果 |
| --- | --- | --- |
| 云上贵州 | `*.icloud.com.cn` | ❌ 413，上限 **1 GiB** |
| 国际区 | `*.icloud.com` | ✅ 200，完整上传 |

详见 [ISS-057](CHANGELOG_ISSUES.md#iss-057-drive-上传上限是云上贵州区特有国际区无此限制)。

- **禁止**在代码里写死一个全局文件大小上限——国际区用户可以正常传 2 GB+，
  硬拦截会让原本能成功的上传失败。
- 区域只能从 **`docws` 主机名**判定（后缀 `.icloud.com.cn`），
  不能用 `.cn` 子串（`/ws/cn` 会误匹配），也不能用语言/locale。
- 判定失败时 **fail-open**：宁可让用户试，也不要误拦合法文件。
- 云上贵州区应在**上传前**拦截并说明是「账号区域限制」，不要让它跑到校验完才报错。

> [!CAUTION]
> **方法论硬规则一：涉及流量闸口的结论必须用真实传输验证。**
> “声明 `Content-Length` 但不发字节”会把客户端阻塞误判为“接受”，本项目因此得过相反结论。
>
> **方法论硬规则二：单环境实测的限制，不得写成全局约束。**
> 必须先问一句“这是不是账号/区域/套餐属性”。本项目已三次因“单点观察 → 全局结论”走弯路
> （[ISS-040](CHANGELOG_ISSUES.md) 手写 bplist、[ISS-052](CHANGELOG_ISSUES.md#iss-052-drive-单次-body-上传大文件被-cdn-以-413-拒绝1gb-不是-clouddocs-的限制)、
> [ISS-056](CHANGELOG_ISSUES.md#iss-056-drive-上传上限实测为-1-gib且无法绕过)）。

### 6.8 实况照片 (Live Photo / Motion Photo) 开发规范
1. **增量更新防覆写**：
   CloudKit 增量同步 (`/records/changes`) 仅返回被修改的 `CPLAsset`，缺省 `CPLMaster`。
   `PhotoRepository` 必须通过 `mergeWithExistingAssets()` 保护已有记录，绝不可将 `LIVE_PHOTO` 冲刷改写为 `IMAGE`。详见 [ISS-036](CHANGELOG_ISSUES.md#iss-036-收藏操作导致-live_photo-媒体类型被冲刷覆写为-image)。
2. **下载单文件打包规范**：
   切勿将实况照片拆分为相册中的两条独立文件（图片+视频）。必须通过 `packJpegMotionPhoto()` 写入 Google Motion Photo APP1 XMP (`GCamera:MotionPhoto="1"`, `GCamera:MicroVideoOffset`)，并将 `.MOV` 视频补充包挂载在 JPEG 尾部，仅向 `MediaStore.Images` 写入唯一一条记录。
3. **直链强制刷新**：
   下载 Live Photo 时若 `videoComplUrl` 为空，必须带 `force = true` 调用 `repository.refreshUrlsForMaster()` 向苹果服务器直拉视频链接，不得依赖 UI 是否触发过预览播放。
4. **EXIF 元数据与像素物理旋转对齐**：
   转码/解码为 Bitmap 时，必须解析原图 `TAG_ORIENTATION` 并通过 `Matrix().postRotate()` 将静态图像素物理旋转至正方向；同时全量复制原图的 Exif 字段（Make/Model/快门/光圈/ISO/时间/GPS），重置 `Orientation = 1`，确保静态图与视频方向 100% 对齐。详见 [ISS-037](CHANGELOG_ISSUES.md#iss-037-实况照片单文件-motion-photo-打包与-exif-防旋转对齐)。

---

## 7. 错误判定规范

> 踩坑记录：ISS-001（Cookie 静默丢弃）、ISS-015（base64 误伤）。

### 7.1 禁止对原始 JSON 做子串匹配

```kotlin
// ❌ 绝对禁止：CloudKit 的 base64 continuationMarker 会随机命中 "401"/"421"
jsonStr.contains("401")
```

必须结构化解析，按优先级：

1. Go 层结构化 `errorCode`（`AUTH_EXPIRED` / `RATE_LIMITED` / `TIMEOUT` / `NETWORK` / `PARSE` / `ZONE_MISSING` / `NOT_FOUND` / `UNKNOWN`）
2. `success == false` 才继续
3. `httpStatus in {401, 421}`
4. 仅对 `errorMessage` 字段用 `\b` 词边界正则匹配 `HTTP 401/421`
5. 解析异常一律返回 `false`（失败即放过，绝不误触发昂贵重建）

> [!IMPORTANT]
> **错误码决定的是「重试还是不重试」，比「是否重建」更重要。**
> `TIMEOUT` / `RATE_LIMITED` / `NETWORK` 必须**退避重试**，绝不能重建会话。
> 月扫描会发出一长串 `/records/query`，一次误判的代价是 `/validate` 往返 + 全部 URL 重签，
> 且会被请求密度放大成重建风暴。实现见 `classifyCoreResponse()` 与 `MonthScanEngine.runScanLoop()`。
>
> 新增错误码只能在 `../go-core/errors.go` 里加，不得在调用点临时拼字符串。

### 7.2 HTTP 状态码从异常对象读，不从文本猜

```kotlin
val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
```

### 7.3 Go 层 `net/http` 对非法 Cookie 是静默丢弃

不含 error 返回，只打一行警告。跨语言传 Cookie 必须 `strings.Trim(v, `"`)` 剥引号，并打印解析成功数量。

---

## 8. 日志与可观测性规范

### 8.1 Logcat Tag 速查

| Tag | 来源 | 用途 |
| --- | --- | --- |
| `ICloudAuth` | Android 鉴权探针 | Cookie 捕获、三要素校验、JNI 调用、区域判定 |
| `PhotoRepository` | Android 数据层 | 会话重建、URL 刷新去重、账号切换清库 |
| `MonthScanEngine` | 月扫描引擎 | 扫描分页、退避重试、月份索引维护 |
| `MonthScanWorker` | 后台扫描 | WorkManager 前台服务与重试 |
| `PhotosViewModel` | 画廊 VM | 月份订阅、扫描进度、可见状态 |
| `ICloudPhotoCoil` | Coil 拦截器 | 单图 load / refresh / ok / fail 全链路 |
| `PhotoDetailViewer` | 详情页 | 播放器错误、图片重试 |
| `MediaStoreDownloader` | 下载器 | 下载进度与换链 |
| `GoLog` | Go 侧 `net/http` | Cookie 丢弃告警、TLS/HTTP 错误 |
| `chromium` | WebView 内核 | 页面 JS 报错、CORS 噪音 |

### 8.2 日志分级约定

- **`Log.i`**：关键业务流程节点（会话重建开始、同步完成、下载成功）
- **`Log.d`**：高频分页扫描/刷新细节、Cookie 落盘对比
- **`Log.w`**：可恢复异常（鉴权判定命中、刷新失败、重试）
- **`Log.e`**：不可恢复异常

> [!CAUTION]
> **绝不打印完整签名 URL** —— 里面含一次性签名参数，可能被日志采集泄露。只打印 `URL.host`。

### 8.3 已知无害噪音（排查时直接忽略）

- `gatewayws.icloud.com.cn/.../batchRegisterToken` 的 CORS 报错
- `wallpaper.svg was preloaded using link preload but not used`
- `MediaCodec / Codec2 / c2.goldfish.*` 系列（模拟器软解）
- `E/MESA: Failed to open rendernode`

---

## 9. 验证纪律（Verification Discipline）

### 9.1 实机验证优先于单测绿

**不要编译通过 + 单测绿就宣布完成。** 本项目多次出现过「编译成功、单测全过」但实机崩溃的案例（ISS-013、ISS-016 均如此）。

修改完毕后必须：
1. `../scripts/build_aar.sh`（如改了 Go）
2. `gradle_build(":app:assembleDebug testDebugUnitTest")`
3. **部署到 `emulator-5554` 并实际点击/滚动验证**
4. **拉 logcat 确认无异常、无预期外的重复请求**

### 9.2 可用工具

| 工具 | 用途 |
| --- | --- |
| `ui_state` | 查看当前屏幕 UI 层级 + 截图 |
| `adb_shell_input` | tap / swipe / keyevent 操作设备 |
| `read_logcat` | 按 `applicationId` 过滤拉取日志 |
| `deploy` | 构建并部署到设备 |

### 9.3 性能验证基线

| 指标 | 期望值 |
| --- | --- |
| 冷启动后 `Rebuilding Go core session` | ≤ 1 次 |
| 连续滚动 3 页的额外重建次数 | 0 次 |
| 同一 masterId 5 分钟内 `/records/lookup` | 1 次 |
| 缩略图 `genStale=true` | 刷新后必须收敛为 `false` |
| 同一 masterId 的 `fetchMetadata ok` | 重复打开信息面板不新增（缓存命中） |
| 查看器首次打开后的掉帧 | 无明显卡顿（打开/关闭动画） |
| 左滑 / 右滑翻页方向 | 左滑 → 下一张，右滑 → 上一张（**必须实机看动画，不能只看最终落点**） |

> [!NOTE]
> 本机环境 `sum.golang.org` 不可达，`scripts/build_aar.sh` 内部的 `go mod tidy` 会失败。
> 需要 `GOSUMDB=off ./scripts/build_aar.sh`。

---

## 10. 代码结构导引（Android 层）

```
app/src/main/java/com/getup/iClouDroid/
├── IClouDroidApp.kt                   # Application，持有 AppContainer（已在 Manifest 注册）
├── MainActivity.kt                    # 顶层状态路由：Checking → spinner；LoggedIn → AppShell；否则 AuthScreen
├── di/
│   ├── AppContainer.kt                # 手写 DI：所有 App 级依赖的唯一创建点
│   └── LocalAppContainer.kt           # Context.appContainer() / rememberAppContainer()
├── data/
│   └── session/SessionManager.kt      # 加密存储 + session_generation 计数器（跨功能共享，留在顶层）
├── feature/
│   └── photos/                        # 照片功能（自包含）
│       ├── data/
│       │   ├── PhotoDatabase.kt        # 照片专属 Room 库（icloud_photos.db）
│       │   ├── PhotoRepository.kt      # 会话守卫 + 三层刷新去重 + 所有 Go 调用入口
│       │   ├── MonthScanEngine.kt      # 全库月扫描（断点续扫/退避重试/月份索引）
│       │   ├── MonthBucketing.kt       # yyyyMM 分桶（设备本地时区）
│       │   ├── MediaStoreDownloader.kt # 流式写入 MediaStore，规避 JNI OOM
│       │   ├── PhotoExif.kt            # EXIF/GPS 模型 + JSON 解析（每一项可空 = 缺失即不渲染）
│       │   ├── db/                     # PhotoAssetEntity(含 yearMonth/exifJson)、PhotoMonthIndexEntity、ScanStateEntity、Dao
│       │   └── image/ICloudPhotoCoil.kt # Keyer 绑 recordName + 拦截器代际判定 + cacheOnly 旁路 + 失败重试
│       ├── ui/                         # PhotosScreen(按月分组的 LazyColumn)、MonthRow、MonthHeader、ScanProgressBar、PhotosViewModel
│       │   ├── MonthIndexBar.kt        # 右侧年月索引：胶囊 + 小窗（竖排年份列 + 3×4 封面月份网格）
│       │   ├── MonthIndexModel.kt      # 索引面板纯逻辑（年份分组 / 步进 / 落点校验，有单测）
│       │   ├── MonthCoverModel.kt      # 月份格子三态（Cover/Placeholder/Unavailable）纯逻辑，有单测
│       │   ├── PhotoViewerScreen.kt    # 全屏查看器（HorizontalPager + 顶部/底部 chrome + 信息卡片）
│       │   ├── PhotoViewerPages.kt     # ImageViewerPage(双层画质过渡/HDR 降级) / VideoViewerPage(窗口化 ExoPlayer)
│       │   ├── PhotoInfoCard.kt        # EXIF 卡片，按字段存在性逐级降级，永不空白
│       │   ├── PhotoInfoFormat.kt      # 光圈/快门/焦距/坐标等纯函数格式化（有单测）
│       │   ├── ZoomState.kt            # 缩放/平移状态 + computeTransform 几何纯函数
│       │   ├── ViewerTransition.kt     # “从哪里来回哪里去”动画 + ThumbnailBoundsTracker
│       │   └── PredictiveBackViewer.kt # 预测返回手势（需 manifest 开关，目前未生效）
│       └── work/                       # MonthScanWorker、ScanScheduler、ScanNotificationHelper
├── feature/local/                     # 本地相册（自包含，为 Phase 3 按文件夹备份预留）
│   ├── data/LocalAlbum.kt             # 模型（含 isBackupEnabled 预留位）
│   ├── data/LocalAlbumRepository.kt   # MediaStore 按 BUCKET_ID 归集文件夹
│   └── ui/LocalAlbumsScreen.kt        # Albums 页（封面卡片 + 媒体权限引导）
├── provider/                          # 系统云端媒体提供方（Android 13+）
│   ├── ICloudMediaProvider.kt         # CloudMediaProvider 实现（游标/分页/预览/全图）
│   └── ShizukuMediaHelper.kt          # Shizuku shell 权限 → 白名单注入 + 一键绑定
└── ui/
    ├── AppShell.kt                    # 登录后的壳：底部导航 + 各功能入口 + 设置 overlay
    ├── PlaceholderScreen.kt           # 未实现功能的占位页
    ├── theme/                         # ThemeMode 枚举 + IClouDroidTheme（深/浅色 + Monet 动态取色）
    ├── navigation/Destinations.kt     # AppDestination 枚举（新增功能在此登记）
    ├── settings/                      # SettingsScreen、SettingsCatalog（设置项登记处）
    └── auth/                          # AuthScreen、AuthViewModel、ICloudWebView、AccountInfoDialog
```

**分层约定**：
- UI 层**不得**直接调 `Icloudcore.*`
- 所有 Go 调用收敛到 `PhotoRepository`
- 所有耗时读写在 `withContext(Dispatchers.IO)` 中执行
- 依赖一律从 `AppContainer` 取，**不得**再新增 `getInstance()` 单例
- 新增 iCloud 功能（Drive / 备忘录）= 在 `feature/` 下建自包含包 + 在 `AppDestination` 与
  `AppShell` 登记 + 在 `AppContainer` 加一个 `lazy` 字段；**不**需改动认证逻辑或其他功能

### 10.0 设置项（Settings）

所有设置项都进**同一个**设置页，入口是「账号信息」sheet 里的**设置**按钮。
新增一个设置项只改三处，不要新建页面：

| 步骤 | 位置 |
| --- | --- |
| 1. 登记枚举 | `ui/settings/SettingsCatalog.kt` → `SettingsItem` |
| 2. 登记分组与顺序 | 同文件 → `SettingsCatalog.groups` |
| 3. 渲染行 + 读写 | 同目录 `SettingsScreen.kt` → `SettingsRow` 的 `when` 分支 |

`SettingsRow` 的 `when` 是**穷尽**的（enum + `when`），漏写分支会编译失败 ——
这比用字符串 key 找配置项可靠得多。

存储统一走 `AppSettings`（`data/settings/`，`SharedPreferences` + `StateFlow`），
不要在 UI 里直接读 `SharedPreferences`。

**全盘扫描的入口已从主页移到设置页。** 主页刷新按钮**只做增量同步**（`syncPhotos()`）；
全盘扫描（`startFullScan()`）必须经过设置页的确认弹窗 —— 它会跑几百个
`/records/query`，中途被杀会让本轮对账作废（[ISS-031](CHANGELOG_ISSUES.md#iss-031)）。

**周期扫描**：`PeriodicScanScheduler`（`feature/photos/work/`）把 `AppSettings.scanFrequency`
映射成 WorkManager 周期任务，首次延迟到凌晨 3 点（`ScanCadence.delayUntilPreferredHour`）。
它与 `ScanScheduler`（一次性补扫）**必须用不同的 uniqueWorkName**，否则手动刷新会把周期任务取消掉。
使用 `ExistingPeriodicWorkPolicy.UPDATE`（不是 `KEEP`），否则用户改设置会静默不生效。

**设置页是 overlay，必须自己接住返回。** 它只是一个布尔状态，Compose 不知道“上面还有一层”。
没有 `BackHandler` 时侧边手势会落到 `MainActivity`，直接**退出应用**（用户报告过的真实 bug）。
`ui/settings/SettingsContainer.kt` 同时接了两路：

| 机制 | 作用 |
| --- | --- |
| `PredictiveBackHandler` | 拖拽时面板跟随手指平移 + 淡出（只动 `graphicsLayer`，不重测量） |
| `BackHandler` | 不支持预测返回的平台 / 普通返回键的兵底 |

三条返回路径（侧边手势、左上角箭头、返回键）**行为必须一致**。新增任何 overlay 页面时，
先把这三条路都接上再接其他。


### 10.1 查看器与元数据（照片详情面板）

**查看器必须是同层级 overlay，不能用 `Dialog`。**
Compose 共享元素（`SharedTransitionLayout`）明确不支持跨 `Dialog` / `ModalBottomSheet`
互操作，因为后者包了 `AndroidView`。`PhotoViewerScreen` 因此作为 `PhotosScreen`
的兄弟节点渲染，信息卡片也是自己画的 `Surface` 而非 `ModalBottomSheet`。

**双层画质过渡：底图必须走 `cacheOnly`。**
网格已经缓存了同一 `recordName` 的 THUMB（key = `icloud_{recordName}_thumb`）。查看器的
底层请求复用这个 key 并置 `cacheOnly = true`（拦截器不换链、请求层 `networkCachePolicy(DISABLED)`），
才能做到“点开即有图”；否则会退化成一次冷缓存往返，比加载圈还慢。

**元数据不进分页。**
`mediaMetaDataEnc` 单张 742~3859 字节，进 `defaultDesiredKeys` 会让全库扫描多传上百 MB。
只在打开信息面板时用 `/records/lookup` 定向拉，并缓存到 `photo_assets.exifJson`。

**`hasMetadata == true` 不等于“有相机信息”。**
截图携带的 plist 只有 `{PNG}` 块，`hasMetadata` 为真但相机/曝光/GPS 全空（实测
`IMG_2122.PNG`：166 字节 payload）。判断“是否要提示无拍摄信息”要看**有没有任一相机字段**，
不能看 `hasMetadata`。

**EXIF 的 GPS 符号必须显式处理。**
Apple 沿用 EXIF 约定：`Latitude` 存数值、`LatitudeRef` 存半球。只读数值会把南半球/西半球
照片定位到镜像位置。

### 10.2 查看器手势与动画

**手势必须全部收在页内同一个 `pointerInput`。**
`detectTransformGestures` 会抢占指针，任何放在祖先节点上的滑动检测器都收不到事件。正确做法是
用 `awaitEachGesture` 自己管理生命周期，在同一循环里分发缩放 / 横向翻页 / 纵向退出。
覆盖层方案（无论置于 Pager 之前还是之后）**都不可行**，两者会互相饿死。

**符号约定以实测为准。**
`calculatePan().x` 在设备上实测：左滑时每帧约 **-11.5**。即：

```
totalDragX < 0  →  左滑  →  下一张
totalDragX > 0  →  右滑  →  上一张
```

而 `scrollBy` 移动的是**内容**，符号相反，预览时要传 `-drag.x`。
这两个约定曾经反复弄反，每次都是靠 `logcat` 逐帧打印才定位的。改动前先打日志，别猜。

**速度只决定“够不够果断”，不参与方向。**
`pagesFromSwipe` 中方向 100% 由 `totalDragX` 决定，速度只看幅值是否超过阈值。
让两者都携带方向是历史上所有翻页反向 bug 的根源。

**`pointerInput` 的 key 不得包含会在手势中变化的状态。**
把 `zoom.isZoomedIn` 当 key 会导致捏合到一半时识别器被重建，表现为“卡住”或“放大后又跳回”。

**打开/关闭动画依赖缩略图矩形。**
网格 `onGloballyPositioned` 上报 `boundsInWindow()`，存进 `ThumbnailBoundsTracker`。
若锚点不存在（缩略图已滚出屏幕），必须降级为淡入淡出，不能飞向失效坐标。
锚点必须跟随 `pagerState.settledPage` 重新解析，否则翻页后关闭会缩回最初打开的那一格。

**转场状态必须上提到 `PhotosScreen`。**
查看器与网格处于不同子树，但必须共用同一个 `progress` 才能互相淡入淡出。
若查看器自己持有一份 `ViewerTransitionState`，两层动画各自跑，交叉淡化会变成“黑屏一下”。

> [!CAUTION]
> **四个已踩过的坑（改动画前必读）：**
>
> 1. **不能用 `Modifier.offset` + `Modifier.size` 做转场。** 它改的是布局尺寸，
>    全屏 pager 每帧重新测量，必卡。必须用 `graphicsLayer` 的 `translation + scale`，
>    并且 `transformOrigin = TransformOrigin(0f, 0f)`（默认轴心在中心，会导致照片从屏幕中心长出）。
> 2. **`Scaffold` 的 `topBar` / `bottomBar` 不能条件渲染。** 切换该 slot 会让 Compose
>    重置其子树的 remember 状态，闭包里的标志位变 stale。本项目因此出现过“底栏挡住信息/下载按钮”。
>    要用普通兄弟节点 + 手动 `PaddingValues` 留白。
> 3. **顶栏/底栏应在 `animateOpen` 之前就隐藏，不是之后。** 转场要等动画结束看起来像“照片已经在
>    标签里了，然后标签栏才消失”两个独立事件。提前隐藏是安全的 —— 底栏留白只影响列表下方空白，
>    不影响缩略图的 `boundsInWindow()`，不会让动画飞错位置。
> 4. **动画期间不能出现纯黑背景。** 遮罩必须分三层：① 网格自己淡出（`alpha = 1 - 0.88p`，
>    留 12% 残余）+ 轻微缩放；② 照片内容 `alpha = p` 缩放长大；③ 全屏遮罩从 `0.55` 升到 `0.92`。
>    任何一层在动画中途达到不透明都可能造成“闪黑”。实测截图（实机，320ms 动画）：
>    动画中途宫格清晰可见、只是变暗后退。

---

## 11. 问题归档与文档维护

| 文档 | 职责 |
| --- | --- |
| [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md) | 已修复问题案例库（ISS-001 ~），含现象/日志证据/根因/方案/验证 |
| [list.md](list.md) | 待修复问题清单（含推荐方案），修完迁往 CHANGELOG |
| [TODO.md](TODO.md) | 分阶段路线图 |
| [TODO_DRIVE.md](TODO_DRIVE.md) | iCloud Drive 开发路线图（Phase 5，协议与 Photos 完全不同） |
| [docs/PITFALLS.md](PITFALLS.md) | 工程避坑指南 |
| [docs/REFERENCE.md](REFERENCE.md) | 参考开源项目源码对照 |

**归档规范**：
- 修复真实 bug 后，必须在 `CHANGELOG_ISSUES.md` 新增条目，并更新顶部索引表
- 若该问题暴露了**通用性陷阱**，同步补充到本文对应章节
- 若已录入 `PITFALLS.md`，在条目中互相引用

---

## 12. 当前进度与下一步

| 阶段 | 状态 |
| --- | --- |
| Phase 0：跨语言工程与编译流水线 | ✅ 完成 |
| Phase 1：混合鉴权与国区/国际区动态路由 | ✅ 完成 |
| Phase 2：照片库拉取、全库月扫描分段、月分组展示与本地缓存（只读预览） | ✅ 完成 |
| Phase 2.5：双向删除同步、全量对账、设置页与周期扫描 | ✅ 完成 |
| Phase 2.6：Glimpse 风相册 UI、日/月视图、Monet 主题、本地相册、系统云端媒体提供方 | ✅ 完成（有已知残留）|
| Phase 3：Android 本地相册自动备份（增量上传） | 🟡 进行中 (Phase 3.1 与 Live Photo 上传已完成，Phase 3.2 备份 UI 与策略开发中) |
| Phase 4：跨生态深度适配（Motion Photo ↔ Live Photo 双向单文件互转与上传下载对齐） | 🟡 进行中 (Live Photo 上传与下载双向互转已完成，详见 [ISS-044](CHANGELOG_ISSUES.md#iss-044-motion-photo--live-photo-自动拆分与双资源-cloudkit-备份上传)) |

### Phase 2.6 交付清单（本轮 UI 与系统集成）

| 能力 | 入口 | 关键实现 |
| --- | --- | --- |
| Glimpse 风相册 UI | 主屏 | 大标题 + 计数徽章 + 日/月切换胶囊 + 8dp 圆角卡片 |
| 日 / 月视图 | 顶部胶囊 | 月为骨架，`DayBucketing` 月内派生日分组；**始终单层 `LazyColumn`** |
| 深浅色 + Monet | 设置 → 外观 | `ThemeMode` + `dynamicLight/DarkColorScheme`（Android 12+）|
| 本地相册管理 | Albums Tab | `LocalAlbumRepository` 读 MediaStore，双/三列卡片 |
| 系统云端媒体源 | 设置 → 系统整合 | `ICloudMediaProvider` + Shizuku 一键激活白名单 |

> [!WARNING]
> **PhotoPicker 宫格缩略图仍有少量空白残留**（快速上滑进入早期年份时）。
> provider 侧已 100% 成功返回完整 JPEG，剩余问题在系统侧的解码/缓存时序。
> 详细柹障记录与待查方向见 [ISS-035](CHANGELOG_ISSUES.md#iss-035photopicker-缩略图空白多层柹障式修复与已知残留)。

### Phase 2.5 交付清单（本轮）

| 能力 | 入口 | 关键实现 |
| --- | --- | --- |
| 云端删除能同步到本地 | 自动（启动/刷新/周期） | `isDeleted` 识别 + 回收站索引 + tombstone |
| 能证明“照片真的不存在了” | 设置 → 全盘扫描 | `scan_seen_ids` + `walkCompleted` + `Reconcile.decide()` |
| 防误删三重闸门 | — | `walkCompleted` / `seen >= local` / 删除前 `Log.w` 审计 |
| 防复活 | 自动 | `purged_ids`（持久化，7 天 TTL + 4000 行 FIFO） |
| 周期扫描频率 | 设置 → 自动扫描 | 日/周/月/自定义/关闭，凌晨 3:00 |
| 设置项扩展框架 | 设置页 | `SettingsCatalog` + `SettingsItem` 枚举（`when` 穷尽） |

> [!IMPORTANT]
> **主页刷新按钮只做增量同步。** 全盘扫描必须走设置页并经过确认弹窗 ——
> 它会跑几百个 `/records/query`，且中途被杀会让本轮对账作废。
> 任何新增的“刷新”入口都必须先想清楚属于哪一类。

### Phase 3 关键提醒

> [!TIP]
> **开工前先读 [docs/REFERENCE.md](REFERENCE.md) 第 3A 节**：那里有逐项核对过的
> “已知可复用项清单”（哪些直接能用、哪些需扩展、哪些完全缺失），能省掉大量重复探索。

1. **iCloud 上传协议无成熟开源实现**，需参考 [docs/REFERENCE.md](REFERENCE.md) 第 3 节逆向
2. **上传前必须查重** —— 重复上传会产生幽灵重复照片，无法删除。Round 2 探针已闭环：
   客户端先算本地 **SHA-256** 去 `/assets/upload` 换 `uploadUrl`，流式上传后由 MMCS 返回
   21 字节 **`fileChecksum`（= `resOriginalFingerprint` = `masterId`）**。因此查重不能“直接用云端现成字段”，
   必须先在本地建指纹索引，云端 `/records/lookup` 只做兜底（详见 §4.6）
3. **上传前校验 `X-APPLE-WEBAUTH-PCS-Photos` Cookie** —— 无此凭证无法写入照片库
   （当前只校验了 `PCS-CloudKit`，需补上）
4. **EXIF 时间戳必须准确** —— iCloud 依赖 `assetDate` 排序，写错导致照片乱序
5. **上传必须走文件路径 + `os.File` 流式**，详见第 3 节第 4 条

### Phase 2 后续偿还的技术债（2026-10 更新）

- [x] **Go 层结构化 `errorCode` / `httpStatus`** —— 已完成，见 [ISS-028](CHANGELOG_ISSUES.md#iss-028-go-层缺结构化错误字段导致-kotlin-靠正则猜)
- [x] **Room 正式 Migration** —— v4 已移除 `fallbackToDestructiveMigration()`，首例为 `MIGRATION_3_4`
- [x] **深翻页跳顶（P2-016）** —— 已根治：移除 Paging 3，改为月分组 + 行展开，锚点 = `monthKey`。见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)
- [x] **双向同步：删除/软删除感知** —— 已实现 `isDeleted` 识别、孤儿 master 过滤、回收站索引拉取、`syncToken` 基线维持与多页增量。见 [ISS-030](CHANGELOG_ISSUES.md#iss-030)
- [x] **全量对账（reconciliation）** —— 扫描从“只增不减”升级为“能证明缺失”：`scan_seen_ids` 暂存表 + `walkCompleted` 显式信号 + `Reconcile.decide()` 保守规则。见 [ISS-031](CHANGELOG_ISSUES.md#iss-031)
- [x] **周期扫描** —— 设置页可配日/周/月/自定义/关闭，预设对齐凌晨 3:00。
  原先这里的“删除感知会延迟”已由 `PeriodicScanScheduler` 解决；
  **但该任务默认是「关闭」**，用户不主动开启时行为与之前一致。
- [ ] **对账失败的可见性** —— `reconcileWithSeenIds()` 被安全闸门拒绝时只打一条 `Log.w`，
  用户在 UI 上看不到“这次扫描没有生效”。候选：在设置页展示最近一次对账结果。
- [x] **`refreshUrlsForMasters()` 批量刷新入口消费** —— 已在 `PhotosViewModel.observeMonth` 完成月份级批量预热，配合 `database.withTransaction` 解决滑动/跳转卡顿，见 [ISS-046](CHANGELOG_ISSUES.md#iss-046画廊缩略图加载主线程掉帧与滑动胶囊跳转重绘轰炸治理)
- [ ] 列表项进入“本月无新照片”状态后可减少索引重建频率

### Phase 3 建议偿还的技术债

- **上传前必须复用 `classifyCoreResponse()`** 的错误码分类，新增上传专属错误码时
  在 `../go-core/errors.go` 注册，不得在调用点临时拼字符串
- **后台同步与月扫描必须互斥** —— 新增的 `PhotoRepository.dataMutationMutex` 已经是现成的锁，
  上传任务必须共用它，而不是另起一把（否则三者会同时写 `photo_assets`）
- **上传统计不进 `scan_state`** —— 那是扫描专用游标表，上传需要自己的进度表
- **未来的“删除照片”功能必须本地优先**。协议上是 `/records/modify` 置 `isDeleted = 1`（软删除），
  流程必须是：① 云端成功 → ② 立即 `deleteByIds` + `rebuildMonthIndexFor` + `evictAssets`
  + `rememberPurged`。顺序不能倒，也不能只打云端；否则在对账前照片会持续显示，看起来像“删不掉”。
  详见 [ISS-031](CHANGELOG_ISSUES.md#iss-031) 与 `PhotoRepository` 里的预留注释。

---
