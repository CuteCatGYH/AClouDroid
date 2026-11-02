# 核心参考开源项目 (GitHub 源码对照表)

> 在开发 Go 协议层与 Android 特性适配时，无需从零抓包，请直接对照以下成熟开源项目的实现逻辑。
>
> 返回 [TODO.md](TODO.md)

---

## 1. Go 语言底座与会话管理 (直接复用/移植)

### [rclone/rclone](https://github.com/rclone/rclone)

- **重点参考路径**：[`backend/iclouddrive/api`](https://github.com/rclone/rclone/tree/master/backend/iclouddrive/api)
- **参考价值**：已用 Go 完整实现 iCloud 的 `Session` 管理、`Trust Token` 自动续期、PCS (Protected Cloud Storage) Cookie 握手以及 HTTP 请求头伪装。
- **注意**：`rclone` 目前仅实现了云盘（`iclouddrive`），未内置照片后端，因此我们只抽取其 `api` 包作为底层网络与鉴权底座。

**本项目已复用点**：

- `Session` 结构设计（见 [ISS-001](CHANGELOG_ISSUES.md#iss-001-cookie-值双引号未剥离导致-go-静默丢弃全部凭证)）
- `/setup/ws/1/validate` 握手流程（见 [ISS-002](CHANGELOG_ISSUES.md#iss-002-accountlogin-调用顺序错误返回-400-missing-apple_id-field)）
- `tap()` Token 静默刷新流程（**尚未移植**，见 TODO Phase 1 遗留项）

---

## 2. CloudKit (`ckdatabasews`) 照片协议逆向字典

### [icloud-photos-downloader/icloud_photos_downloader (`icloudpd`)](https://github.com/icloud-photos-downloader/icloud_photos_downloader)

- **重点参考路径**：`src/pyicloud_ipd/services/photos.py`
- **参考价值**：目前社区维护最活跃的 iCloud 照片同步工具。完美展示了如何处理国区（`icloud.com.cn`）路由、CloudKit `PrimarySync` 分区初始化、游标分页拉取以及 Live Photo 下载。
- **本项目用途**：Phase 2 的 `/records/query` 分页实现、Phase 4 的 Live Photo 下载

### [picklepete/pyicloud](https://github.com/picklepete/pyicloud)

- **重点参考路径**：`pyicloud/services/photos.py`
- **参考价值**：Python 版 iCloud 协议鼻祖，适合快速查阅 `CPLAsset` 与 `CPLMaster` 的 JSON 字段映射关系。
- **本项目用途**：字段字典速查

### [steilerDev/icloud-photos-sync](https://github.com/steilerDev/icloud-photos-sync)

- **参考价值**：展示了如何利用 CloudKit 的 `syncToken` / `zoneChangeToken` 获取增量变更（Delta Sync），而非每次全量遍历图库。
- **本项目用途**：Phase 2 增量同步实现

---

## 2A. 本项目实测的字段字典 (云上贵州账号)

> **来源**：2026-10 在真实账号上跑的一次性探针（`/records/lookup` **不带 `desiredKeys`**，
> 以探针服务器实际存储的全部字段）。所有结论均为实测，非推测。
> 探针代码已删除，结论保留在本节。

### 2A.1 `CPLMaster` 完整字段列表

从一条真实照片的 master 记录中实测到 32 个字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recordName` | string | 主键，对应项目中的 `masterId` |
| `itemType` | string | UTI，如 `public.heic` / `public.jpeg` |
| `filenameEnc` | base64 | 文件名（需 base64 解码） |
| `originalOrientation` | int | 原始旋转角 |
| `importDate` | date | 导入 iCloud 的时间 |
| `originalCreationDate` | date | **拍摄时间（EXIF 原始）** |
| `recordModificationDate` | date | 记录最后修改时间 |
| **`mediaMetaDataEnc`** | base64 | **EXIF/GPS 元数据（binary plist）→ 见 2A.2** |
| **`mediaMetaDataType`** | string | 固定为 `CGImageProperties` |
| **`resOriginalFingerprint`** | string | **原图指纹 = `recordName` = MMCS `fileChecksum`**（21 字节 Base64，见 §3A.4） |
| `resJPEGMedFingerprint` | string | 中图指纹 |
| `resJPEGThumbFingerprint` | string | 缩略图指纹 |
| `resOriginalRes` / `resJPEGMedRes` / `resJPEGThumbRes` | ASSETID | 三类资源（见 2A.3） |
| `resOriginalWidth/Height` | int | 原图尺寸 |
| `resJPEGMedWidth/Height` | int | 中图尺寸 |
| `resJPEGThumbWidth/Height` | int | 缩略图尺寸 |
| `resOriginalFileType` / `resJPEGMedFileType` / `resJPEGThumbFileType` | string | MIME |
| `resOriginalFileSize` / `resJPEGMedFileSize` / `resJPEGThumbFileSize` | int | 字节数 |
| `dataClassType` | int | 数据分类（照片/视频） |
| `videoFrameRate` | int | 视频帧率 |
| `fullSizeJPEGSource` | string | 全尺寸 JPEG 来源 |
| `importedBy` | ref | 导入设备/应用引用 |
| `importedByBundleIdentifierEnc` | base64 | **导入应用 Bundle ID**（可用于分类） |
| `importedByDisplayNameEnc` | base64 | 导入应用显示名 |

> [!NOTE]
> `mediaMetaDataEnc` 是 **精简后的元数据**（实测 742~3859 字节），不是完整 EXIF 块。
> Apple 只保留了展示需要的子集，这反而降低了传输与解析开销。

### 2A.2 `mediaMetaDataEnc` 内部结构

- **编码**：base64 外层，解出后是 **binary plist**（魔数 `bplist00`）
- **顶层字典**：与 Apple `CGImageProperties` 常量同构

| 子字典 | 实测包含的键 |
| --- | --- |
| `{TIFF}` | `Model`、`DateTime`、`ResolutionUnit`、`XResolution`、`YResolution`、`PixelXDimension`、`PixelYDimension` |
| `{Exif}` | `DateTimeOriginal`、`FNumber`、`ExposureTime`、`ISOSpeedRatings`、`FocalLength`、`FocalLenIn35mmFilm`、`LensModel`、`BrightnessValue`、`ExposureBiasValue`、`Make`、`Model` |
| `{GPS}` | `Latitude`、`Longitude`、`Altitude`、`Speed`、`ImgDirection`、`HPositioningError`、`DateStamp`、`TimeStamp` |
| `{PNG}` | `DPIWidth`、`DPIHeight`、`ColorModel`、`ProfileName`、`Orientation` |
| 其他 | `{IPTC}`、`PrimaryImage`、`Headroom`、`Depth` |

**实测样本分布**（扫描 25 条记录）：

| 类型 | 字节数 | 是否带 `{GPS}` | 数量 |
| --- | --- | --- | --- |
| 相机拍摄（开启定位） | 3775~3859 | 是 | 8 条 |
| 截图 / 未开定位 | 742~1035 | 否 | 17 条 |

> [!IMPORTANT]
> **`{GPS}` 字典不是必然存在的。** 截图、或拍摄时关闭了定位服务的照片都没有。
> 任何详情面板必须按字段存在性条件渲染，不能假设 GPS 恒有。

### 2A.3 资源对象（ASSETID）的子键

```json
"resOriginalRes": ["size", "wrappingKey", "referenceChecksum", "downloadURL", "fileChecksum"]
```

| 子键 | 用途 |
| --- | --- |
| `downloadURL` | 临时直链，寿命 ~15-18 分钟（见 [ISS-026](CHANGELOG_ISSUES.md#iss-026)） |
| `size` | 字节数 |
| `fileChecksum` | 内容校验和 |
| `referenceChecksum` | 引用校验和 |
| `wrappingKey` | 加密密钥材料 |

### 2A.4 `CPLAsset` 上的位置字段

除 master 的 EXIF 之外，`CPLAsset` 自身还带：

```
assetDate, masterRef, locationEnc
```

> [!TIP]
> `locationEnc` 是**独立的位置字段**。只需判断“有没有位置”而不需要解码整个 plist 时，
> 直接读它即可，无需解析 `mediaMetaDataEnc`。

### 2A.5 对后续功能的影响

| 功能 | 数据来源 | 可行性 |
| --- | --- | --- |
| 照片详情面板 | `mediaMetaDataEnc` | ✅ 光圈/快门/ISO/焦距/镜头 + GPS |
| 列表位置标记 | `CPLAsset.locationEnc` | ✅ 无需解码 plist |
| **Phase 3 上传查重** | 本地指纹索引 + `photo_assets.masterId` + `/records/lookup` | ✅ 已闭环（两阶段，见 §3A.4） |
| Albums 按来源分类 | `importedByBundleIdentifierEnc` | ✅ 区分拍摄 vs 导入 |

**唯一未解决的工程问题**：Go 标准库没有 binary plist 解析器。
建议引入 [`howett.net/plist`](https://pkg.go.dev/howett.net/plist)（纯 Go、无 CGO，兼容 gomobile）。

---

## 3. CloudKit 照片上传协议参考 (攻克上传难点)

### [simulot/immich-go](https://github.com/simulot/immich-go) / [bbernhard/icloud-photos-uploader](https://github.com/bbernhard/icloud-photos-uploader)

- **参考价值**：由于 `icloudpd` 仅支持下载，上传照片至 iCloud 的 `records/modify` 与 `/assets/upload` (MMCS 对象存储分块上传) 报文结构需参考此类上传工具。
- **备选方案**：直接通过桌面浏览器在 `icloud.com.cn` 手动上传一张图进行抓包对照。
- **本项目用途**：Phase 3 流式上传链路

### 本项目已实测通过的端到端上传三步法（Round 2 探针，2026-10）

> **已验证闭环**：端到端返回 `200 OK`，且上传后 `syncIncremental()` 能把新 asset 拉回本地
> `photo_assets`。**大文件不需要分片**（已实测 16MB 图片与 105MB 视频）。

| 步骤 | 接口 | 关键点 |
| --- | --- | --- |
| ① 申令牌 | `POST /assets/upload` | 传本地文件的 **SHA-256**（Base64，44 字符）作 `signature`；返回 `recordName`（UUID，**仅本次会话票据**）与 `singleFileUpload` 直链（`cws.icloud-content.com.cn`） |
| ② 传字节 | `POST {uploadUrl}`（`application/octet-stream`） | **零内存流式**：`http.NewRequest(POST, uploadUrl, *os.File)` + 显式 `req.ContentLength = fileInfo.Size()`，严禁 `os.ReadFile`/`io.ReadAll`（[PITFALLS §4](PITFALLS.md)）。返回 `receipt.singleFile`（含 `fileChecksum` / `referenceChecksum` / `wrappingKey` / `size`） |
| ③ 提交记录 | `POST /records/modify` | **同一个 `operations` 数组内原子提交两条 `create`**：先 `CPLMaster`（`recordName` = `receipt.singleFile.fileChecksum`，**不是 UUID**；`resOriginalRes` = `receipt` 对象；`resOriginalFingerprint` = 同一值），再 `CPLAsset`（`masterRef` 指向该 master） |

> [!CAUTION]
> **只建 `CPLMaster` 不建 `CPLAsset`，照片在任何客户端都不会显示。**
> 项目在 [ISS-030](CHANGELOG_ISSUES.md#iss-030) 中已严令丢弃“未配对的孤儿 master”，
> 所以单建 master 等于把照片扔进黑洞。

---

## 3A. Phase 3 已知可复用项清单

> **编写时点**：2026-10，基于 Phase 2 完成后的代码现状逐项核对（非推测）。
> 开工前请重新确认这些位置未被重构。

### 3A.1 可直接复用（无需改动）

#### Go 协议底座

上传链路的“外壳”全部现成 —— 上传只是换一个 `path` 去请求，Cookie、Header、路由都已解决。

| 能力 | 位置 | 为何可直接用 |
| --- | --- | --- |
| `Session.NewRequest` | `go-core/session.go` | 自动注入 Cookie + 匹配区域的 `Origin`/`Referer` |
| `Session.CookieMap` | `go-core/session.go` | 跨子域手动注入，绕开 `com.cn` 公共后缀问题 |
| `photosBaseURL(path)` | `go-core/photos.go` | 拼 CloudKit URL（含 `clientId`/`dsid`/`remapEnums`） |
| `errors.go` 全套 | `go-core/errors.go` | `ClassifyHTTPStatus` / `ClassifyNetErr` + 8 个错误码 |
| `ExtractAndSaveCookies` | `go-core/session.go` | 上传过程中的 Cookie 轮换会自动保存 |
| `ClientID` 持久化 | `go-core/session.go` | 上传同样需要稳定 clientId |

#### Android 基础设施

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| `ensureGoSession()` | `PhotoRepository` | 会话守卫，上传前必过 |
| `classifyCoreResponse()` | `PhotoRepository` | 错误分类，**上传错误码必须在此扩展** |
| `dataMutationMutex` | `PhotoRepository` | 写操作互斥锁，**上传必须共用这把，不得另起** |
| `isWipeInProgress()` | `PhotoRepository` | 登出守卫，上传中遇到登出要立即放弃 |
| WorkManager + 前台服务 + 通知 | `feature/photos/work/` | `MonthScanWorker` / `ScanNotificationHelper` / `ScanScheduler` 可直接当模板 |
| 周期调度 | `PeriodicScanScheduler` | 加个 `ContentObserver` 触发器即可 |
| Room Migration 范例 | `PhotoDatabase.MIGRATION_3_4` | 新增上传进度表时照抄 |
| 手写 DI | `AppContainer` | 加个 `lazy` 字段即可 |

### 3A.2 可复用但需扩展

**① binary plist 解析器已存在**

`FetchPhotoMetadata` 内部的 `decodeMediaMetaData` 已能把 `mediaMetaDataEnc` 解成结构化 EXIF/GPS。
P3 读本地照片 EXIF 时可沿用同一套解析思路（虽然输入源不同）。

**② `MediaStoreDownloader` 是写入方向的现成教材**

它已在做**写 MediaStore**：`ContentValues`、`RELATIVE_PATH`、`IS_PENDING`、
`DATE_ADDED` / `DATE_MODIFIED` 的用法可直接参考（P3 是反向读，但列名与权限模型一致）。

其中 `copyExifAttributes(srcExif, dstExif)` 说明项目已有 `ExifInterface` 读写经验，
P3 用它读 `DateTimeOriginal` 正好。

**③ 批量 `/records/lookup` 模式**

`RefreshAssetUrls` 的请求构造、分批、错误处理可照搬到查重场景。

### 3A.3 完全缺失（P3 主体工作量）

| 缺口 | 现状 |
| --- | --- |
| **任何写操作能力** | Go 侧**完全没有** `/records/modify`、`/assets/upload`、MMCS 分块上传 |
| **指纹索引未建立** | `resOriginalFingerprint` 等于 `masterId`，已经以 `photo_assets.masterId` 形式存在；缺的是 **本地文件 → 指纹** 的反向索引（MMCS 算法或 `sha256` 映射表） |
| **上传进度表** | `scan_state` 是扫描专用（AGENTS 明确禁止复用），需新建 |
| **MediaStore 读取 + ContentObserver** | 全项目 **0 处** `ContentObserver`，目前只下载不监听 |
| **`PCS-Photos` Cookie 校验** | 当前只校验 `PCS-CloudKit`，无 `PCS-Photos` 校验 —— 这是 AGENTS §12 的红线要求 |

### 3A.4 三个开工前必须先想清楚的问题

> [!IMPORTANT]
> **① 指纹链路已闭环（Round 2 探针），不再是未知数。**
>
> 先把两个“哈希”分开，它们不是一回事：
>
> | 哈希 | 长度 | 谁产生 | 用途 |
> | --- | --- | --- | --- |
> | **SHA-256** | 32 字节 / 44 字符 Base64（`=` 结尾） | 客户端 | 仅作 `/assets/upload` 的**签名** |
> | **MMCS `fileChecksum`** | 21 字节 / 28 字符 Base64（`A` 开头，首字节 `0x01`） | 服务端（上传后） | **即 `resOriginalFingerprint`，即 `masterId`** |
>
> 实测铁律：`masterId == resOriginalFingerprint == receipt.singleFile.fileChecksum`。
>
> 查重因此分两阶段：
> 1. **第 1 层（本地，零网络）**：在 Go 层复现 MMCS 21 字节指纹算法，拿结果直接查
>    `photo_assets.masterId`；暂不做算法时退化为 `(filePath, fileSize, lastModified, sha256) → masterId`
>    本地映射表（代价：识别不了其他 Apple 设备已上传过的同一张原图）
> 2. **第 2 层（云端兜底）**：`/records/lookup`，`recordName = fingerprint`，`desiredKeys = ["recordName"]`

> [!WARNING]
> **② 现有的“删除”不是主动写云端，不能当上传模板。**
>
> ISS-031 的实现是**被动对账**：发现云端 `isDeleted=1` 后清理本地，
> 并未发 `/records/modify`。所以它只能提供“软删除”这个概念，
> **无法**作为上传写操作的代码模板。

> [!CAUTION]
> **③ `resOriginalFingerprint` 不要直接加进 `defaultDesiredKeys`。**
>
> 那会让**每一次分页查询**的响应都变大（全库扫描有上百次请求）。
> 而且它本来就等于 `recordName` —— 查重时连这一列都不必取，`desiredKeys` 只写 `["recordName"]` 即可。

---

## 4. Android 端动态照片 (Motion Photo) 拆解与合成参考

### [deckerst/aves](https://github.com/deckerst/aves)

- **备选参考**：Android 官方 `ExifInterface` XMP 规范
- **参考价值**：处理三星等 Android 设备的 Motion Photo（单文件尾部追加 MP4 封装）与苹果 Live Photo（`HEIC/JPG` + `.MOV` 双文件通过 `ContentIdentifier` 绑定）之间的容器拆解与 XMP 元数据解析。
- **本项目用途**：Phase 4 跨生态动态照片互转

---

## 5. iCloud Drive 实测字段字典 (D0 探针结论)

> **来源**：2026-11，真实账号（云上贵州，`dsid=20118126497`）
> 一次性 `androidTest` 探针（进程内调用，因为 Cookie 在加密存储里）。
> **探针代码已删除**（PITFALLS §15），结论保留于此。
> 完整报文与逐步结果存档在 `.artifacts/drive-d0-findings.artifact.md`。

### 5.0 端点

| 服务 | 端点 | 用途 |
| --- | --- | --- |
| `drivews` | `https://pXX-drivews.icloud.com[.cn]` | 目录列举（`retrieveItemDetailsInFolders`） |
| `docws` | `https://pXX-docws.icloud.com[.cn]` | 下载（`/ws/com.apple.CloudDocs/download/by_id`） |
| `ckdatabasews` | 同 Photos | CloudKit 层（D3 可能用得上） |

两者都从 `/validate` 的 `webservices` 直接取得，**不需要额外握手**。
`Session.DrivewsURL` 已落地（`account.go` 的 `handleValidateResponse`）。

### 5.1 节点类型：用 `type`，没有 `bundleType`

> [!CAUTION]
> **`bundleType` 字段不存在。** 41 个子项中一个都没有。
> 任何「用 bundleType 区分文件夹与文档包」的设计都必须作废。

| `type` | 含义 | 可否下钻 |
| --- | --- | --- |
| `FOLDER` | 普通文件夹 | ✅ |
| `FILE` | 文件 | ❌ |
| `APP_LIBRARY` | **App 文档容器**（列在根目录，带 App 图标） | ✅ |

**`APP_LIBRARY` 不是 iWork 文档包**，而是第三方/系统 App 的 iCloud 同步目录。
实测样本：Keynote、Preview、GarageBand、Shortcuts、Shadowrocket、Days Matter、StressWatch。

特征：

| 特征 | 值 |
| --- | --- |
| `docwsid` | 恒为字面量 **`"documents"`** |
| `zone` | 应用自己的容器（`com.apple.Keynote` 等），**不是** `com.apple.CloudDocs` |
| `icons[]` | App 图标，走 `https://pXX-drivews.../getIcons?id=<zone>&field=icon120x120_iOS` |
| `supportedTypes` / `supportedExtensions` | 该 App 支持的类型 |

### 5.2 `drivewsid` 格式（最重要的约定）

```
<TYPE>::<zone>::<docwsid>
```

| 例子 | 说明 |
| --- | --- |
| `FOLDER::com.apple.CloudDocs::root` | 根目录，`docwsid` 是字面量 `root` |
| `FOLDER::com.apple.CloudDocs::<UUID>` | 普通文件夹 |
| `FILE::com.apple.CloudDocs::<UUID>` | 普通文件 |
| `FOLDER::com.apple.Keynote::documents` | Keynote 的 App 容器 |

> [!IMPORTANT]
> `parentId` 存的是**完整的 `drivewsid`**，不是裸 UUID。
> 发请求时也只接受完整形式 —— 传裸 `"root"` 会得到 `{"errorReason":"Bad Request"}`。

### 5.3 节点字段覆盖度（41 个子项统计）

| 字段 | 覆盖 | 适用 | 说明 |
| --- | --- | --- | --- |
| `drivewsid` / `docwsid` / `zone` / `name` / `parentId` / `etag` / `type` / `dateCreated` | 41/41 | 全部 | 通用 |
| `item_id` | 34/41 | FOLDER + FILE | CloudKit 内部 id |
| `size` / `dateModified` / `dateChanged` | 24/41 | FILE | 字节数 / 时间戳 |
| `extension` | 23/41 | FILE | 扩展名（无点） |
| `lastOpenTime` | 20/41 | FILE | 最近打开时间 |
| `assetQuota` / `fileCount` / `shareCount` / `shareAliasCount` / `directChildrenCount` | 10/41 | FOLDER | 目录统计 |
| `maxDepth` / `icons` / `supportedTypes` / `supportedExtensions` | 7/41 | APP_LIBRARY | 容器元数据 |

> [!NOTE]
> `dateCreated` 格式为 ISO8601 UTC（`2026-02-05T05:57:15Z`）。
> 与 Photos 的 epoch 毫秒完全不同 —— 不要复用 `assetDate` 的解析函数。

### 5.4 zone 分布（真实账号）

| zone | 数量 |
| --- | --- |
| `com.apple.CloudDocs` | 34（用户自己的 Drive） |
| `com.apple.Keynote` / `com.apple.Preview` | 各 1 |
| `iCloud.is.workflow.my.workflows` | 1（快捷指令） |
| `iCloud.com.<vendor>.<app>` | 4（第三方 App） |

**产品结论**：根目录应当是「用户文件夹 + 一列 App 容器」，两者视觉上要区分开。

### 5.5 已实测通过的请求格式

#### 列举目录

```
POST https://pXX-drivews.icloud.com[.cn]/retrieveItemDetailsInFolders
Content-Type: application/json

[{"drivewsid":"FOLDER::com.apple.CloudDocs::root","partialData":false,"includeHierarchy":false}]
```

> [!CAUTION]
> **三个已验证的坑：**
> 1. 加 `/ws/com.apple.CloudDocs/` 前缀 → **400 且 body 长度为 0**
>    （网关层直接拒绝，连 handler 都没进）
> 2. `drivewsid` 只写 `"root"` → `{"errorReason":"Bad Request"}`
> 3. 响应是**顶层数组**，不是 `{"items":[...]}`
>    （按后者解析会得到空列表且**不报错** —— 与 PITFALLS §16 同一类失效）

响应结构：

```json
[{
  "drivewsid": "FOLDER::com.apple.CloudDocs::root",
  "docwsid": "root",
  "zone": "com.apple.CloudDocs",
  "type": "FOLDER",
  "directChildrenCount": 35,
  "items": [ /* 直接子项，同结构但无 items */ ]
}]
```

> [!NOTE]
> **无 query 参数**：不需要 `dsid`、`clientId`、`clientBuildNumber`。
> 账号完全由 Cookie 识别。（Photos 的 CloudKit 接口全都需要，别照抄。）

#### 下载令牌

```
GET https://pXX-docws.icloud.com[.cn]/ws/com.apple.CloudDocs/download/by_id?document_id=<docwsid>
```

```json
{
  "document_id": "<docwsid>",
  "item_id": "CKH_...",
  "owner_dsid": "...",
  "data_token":      { "url": "https://cvws.icloud-content.com[.cn]/B/...", "token": "...", "signature": "...", "wrapping_key": "...", "reference_signature": "..." },
  "thumbnail_token": { "url": "...", "token": "...", "signature": "...", "wrapping_key": "...", "reference_signature": "..." },
  "double_etag": "gqp::gm9"
}
```

> [!IMPORTANT]
> `data_token.url` 落在 **`cvws.icloud-content.com[.cn]`**，与 Photos 是**同一个 CDN**。
> 因此 AGENTS §6 的 `URL_MAX_AGE_MS = 12min` 与三层刷新去重**大概率可直接复用** ——
> 但寿命结论**必须先在 D4 用 curl 实测**（PITFALLS §3：这类结论不许猜）。
>
> `thumbnail_token` 是白送的：D7 的缩略图不需要自己生成。

### 5.6 已确认**不存在**的能力

| 能力 | 证据 |
| --- | --- |
| `changes/database`（结构增量） | `404 WSModuleNotFoundException: "Module changes could not be found."` |

> [!WARNING]
> 这**推翻**了 `docs/TODO_DRIVE.md` §5（D3）里「`changes/zone` + `changes/database` 两路增量」的设计前提。
> D3 已重新侦察完毕，结论见 §5.7。

### 5.7 结构增量机制（D3 侦察结论）

> **来源**：2026-11，同一真实账号，`androidTest` 进程内探针。探针代码已删除。

#### 结论：Drive 没有全局游标 delta，**用 `etag` 轮询**

共尝试了 13 种请求形态，全部有对照实验（用已知可用的 CloudKit 主机做 control）。

| 候选 | 结果 | 判定 |
| --- | --- | --- |
| `changes/zone` POST（空 token） | 404 `WSModuleNotFoundException` | ❌ 不存在 |
| `changes/zone` POST（zones body） | 404 同上 | ❌ |
| `changes/zone` GET | 404 同上 | ❌ |
| `changes/database` GET | 404 同上 | ❌ |
| `changes/database` POST + dsid | 404 同上 | ❌ |
| `records/changes` on CloudDocs zone | 401 `AUTHENTICATION_FAILED: no auth method found` | ❌ 无此 zone |
| `zones/list` on ck host for CloudDocs | 401 同上 | ❌ 无此 zone |
| docws + CloudKit 路径 | 401 空 body | ❌ docws **不是** CloudKit DB |
| **对照**：ck 主机 `zones/list` | **200 / 469 B** | ✅ 证明探针本身没写错 |
| **`retrieveItemDetails`** | **200 / 284 B**（`{"items":[...]}` 包裹） | ✅ **可用** |
| 根目录重列两次比 `etag` | `nrp` vs `nrp`，35 项 / 701 文件均一致 | ✅ **稳定** |

#### 三条硬结论

**① `changes/*` 这条线彻底死透。** 不是名字问题：
所有形态（POST/GET、空 token / zones body / dsid）都返回同一个
`WSModuleNotFoundException`。Drive **没有** CloudKit 式的全局变更游标。

**② `com.apple.CloudDocs` 不是 CloudKit zone。**
拿**已知能用**的 CloudKit 主机（Photos 的 `ckdatabasews`）去查它，得到
`401 AUTHENTICATION_FAILED: no auth method found`；同主机对 `com.apple.photos.cloud` 却返回 200。
对照成立，所以这是真的“不存在这个 zone”，不是路径拼错。

**③ `retrieveItemDetails` 是那个“轻量变体”，但 body 形状不同（又是个坑）。**

| 端点 | body 形状 |
| --- | --- |
| `retrieveItemDetailsInFolders` | **裸数组** `[{...}]` |
| `retrieveItemDetails` | **包裹** `{"items":[{...}]}` |

> [!CAUTION]
> 两个端点只差个名字，body 形状却不同。用裸数组打 `retrieveItemDetails`
> 得到的是 `400 {"errorReason":"Bad Request","wantDiagnostics":true}` ——
> 是**处理层解析失败**，不是端点不存在。这个 400 和“端点没有”很难区分。

#### 落地到 D3 的设计

**采用「目录级 etag 差量列举」，而不是全局游标同步：**

1. 只在 **`etag` 变化**（或 `directChildrenCount` 变化）时重列该目录
2. `etag` 存在每个节点上（`FOLDER` / `FILE` / `APP_LIBRARY` 都有），
   而且稳定（同内容两次列举完全一致）
3. `retrieveItemDetails` 不返回子项，正好用来**廉价地探一个节点的当前 etag**

> [!TIP]
> 对几千文件的量级这完全够用，且比游标同步更好调：
> 出问题时只需回答“哪个目录的 etag 没变”这一个问题。
> 代价是**发现不了“从未展开过的目录里新增的文件”** —— 但那类目录本来就没缓存，
> 展开时必然全量拉，不丢东西。

#### 仍未验证

- **删除**的表示方式：Drive 的“最近删除”很可能是个独立 zone（类似 Photos 的回收站），
  未在本轮探明。D3 实现删除感知前需补一轮。
- `etag` 的**作用域**：目前只验证了它稳定，未验证“子文件内容变化是否会 bump 父目录 etag”。
  若不会，则内容变化检测仍需要 `retrieveItemDetails` 逐节点比对。

---

### 5.8 第二轮侦察：回收站 / etag / 上传（D3b）

> **来源**：2026-11，同一账号，`androidTest` 进程内探针（两轮）。探针代码已删除。

#### A. 回收站：仍然没找到入口

| 尝试 | 结果 |
| --- | --- |
| `trash` / `Trash` / `root_trash` 作为 `drivewsid` | `200`，但每项 `{"items":[],"status":"ID_INVALID"}` |
| `/ws/com.apple.CloudDocs/trash/items` | 404 `Module trash could not be found` |
| `/ws/com.apple.CloudDocs/list/trash` | 404 `WSHandlerNotFoundException: could not find handler for endpoint 'POST trash'` |
| `/ws/com.apple.CloudDocs/changes/trash` | 404 同上（changes） |

> [!IMPORTANT]
> 注意 `ID_INVALID` 和 `WSHandlerNotFoundException` 的**区别**：
> 前者说明“路径对了、id 不存在”（`retrieveItemDetailsInFolders` 正常工作只是找不到这个 id），
> 后者说明“这个 handler 压根没有”。
> 两条信息指向同一个结论：Drive 回收站**不是** docws 上的一个模块。
>
> **仍未找到入口。** 可能路径：网页端的回收站走的是另一个前端专用 endpoint，
> 或者在 `ckdatabasews` 的某个 zone 里。D3 若不做删除感知，可以绕开；
> 要做就必须再从网页端抓一次包。

#### B. `etag` 作用域：稳定，但只证实了前半句

样本：`Desktop` 目录，19 个子项，连续两次列举（间隔 250ms）：

```
etag compare: 19 children, all etags identical across 2 listings
```

`retrieveItemDetails` 单独查该目录返回 `etag=hnz`，与列表里的一致。

> [!WARNING]
> **只证明了“没变化时 etag 稳定”。**
> “子文件内容变了会不会 bump 父目录 etag” **没验** —— 那需要真去改一个文件。
> 因此在验证之前，**文件夹级 etag 轮询不能用来检测文件内容变化**，
> 只能用来检测「目录结构（增/删/改名）」的变化。

#### C. 上传：令牌已打通，请求形状是逐个字段试出来的

服务端**按固定顺序**校验字段，每次只报第一个缺失项。这反而是最快的探明方式：

| 尝试 | 服务端原话 |
| --- | --- |
| `{filesize, filesignature}` | `must specify document type in upload request` |
| `+ type:FILE` | `filename field required to upload file` |
| `+ filenameEnc`（base64） | 仍然报 `filename field required` —— **`filenameEnc` 不认，要明文 `filename`** |
| `+ filename`（明文） | `content_type field required to upload file` |
| **`+ content_type`** | **✅ 200 —— 拿到上传令牌** |

**最终可用请求体**（`POST {docws}/ws/com.apple.CloudDocs/upload/web`）：

```json
{
  "filesize": 44,
  "filesignature": "<SHA-256 base64>",
  "type": "FILE",
  "filename": "probe_upload.txt",
  "content_type": "text/plain"
}
```

**响应**：

```json
[{
  "document_id": "99509BE9-...",
  "url": "https://cws.icloud-content.com.cn:443/20118126497/singleFileUpload?tk=...&ref=...&c=com.apple.clouddocs&z=com.apple.CloudDocs&uuid=...&dataclass=com.app",
  "owner": "20118126497",
  "owner_id": "_b7c0d2c4..."
}]
```

> [!IMPORTANT]
> **字段名踩坑记录：**
>
> | 直觉 | 实际 |
> | --- | --- |
> | `filenameEnc`（呼应 Photos 的 `filenameEnc`） | ❌ 不认，要**明文** `filename` |
> | `content_type` 可省 | ❌ 必填 |
> | `type` 填 UTI（`public.plain-text`） | 用 `FILE` 就够了（v7 成功）；UTI 未单独测 |
>
> `url` 与 Photos 的 `/assets/upload` 同构（都是 `cws` / `singleFileUpload`），
> **但 Drive 返回的是一个数组**，而 Photos 返回的是 `singleFileUpload` 对象。

#### D. 服务权限（为系统文件选择器做准备）

本账号 `status == active` 的 31 个服务（节选相关）：

```
docws, drivews, ckdatabasews, cksharews, ubiquity,
iworkexportws, iworkthumbnailws, keyvalue,
notes, photos, photosupload, sharedlibrary, sharedstreams
```

> [!TIP]
> `iworkexportws` + `iworkthumbnailws` 存在，意味着 iWork 文档包
> **有服务端提供的导出/缩略图接口**，不必客户端自己解包。
> `cksharews` 存在，意味着共享文件有独立的 CloudKit 入口。

---

### 5.9 全部端点逐个实测（第三轮）

> 对照 rclone 的 16 个端点逐一实测。**全部亲自验过**，不依赖第三方仓库。

#### 总表

| # | 端点 | 方法 | 结果 |
| --- | --- | --- | --- |
| 1 | `{drivews}/retrieveItemDetails` | POST | ✅ 200（`{"items":[…]}` 包裹） |
| 2 | `{drivews}/retrieveItemDetailsInFolders` | POST | ✅ 200（**裸数组**） |
| 3 | `{docws}/ws/com.apple.CloudDocs/list/lookup_by_path?unified_format=true` | POST | ✅ 200，body 是 **JSON 字符串** `"/Documents"` |
| 4 | 同上 `?unified_format=false` | POST | ✅ 200，返回另一套字段（`document_id` / `mtime` / `deleted`…）|
| 5 | `{docws}/v1/item/{item_id}` | GET | ✅ 200，含 `item_info.urls.url_download` |
| 6 | `{docws}/v1/enumerate/{item_id}?limit=N` | GET | ✅ 200，但返回 **`{}`**（该 id 是文件不是目录） |
| 7 | `{docws}/ws/com.apple.CloudDocs/download/by_id?document_id=` | GET | ✅ 200，`data_token.url` |
| 8 | `data_token.url`（`cvws` CDN） | GET | ✅ **206**（`Range: bytes=0-0`） |
| 9 | `{drivews}/moveItemsToTrash` | POST | ✅ **200 —— 回收站入口找到了** |
| 10 | `{drivews}/createFolders` | POST | ✅ 200，返回新文件夹含 `drivewsid`/`etag` |
| 11 | `{drivews}/renameItems` | POST | ✅ 200，`etag` 递增 |
| 12 | `{drivews}/moveItems` | POST | ✅ 200 |
| 13 | `{docws}/v1/item/copy/{item_id}` | POST | ✅ 200（**会真的复制**） |
| 14 | `{docws}/ws/com.apple.CloudDocs/upload/web` | POST | ✅ 200，拿到上传令牌 |
| 16 | `{docws}/ws/com.apple.CloudDocs/update/documents` | POST | ✅ 200（`command` 必填） |

#### 关键结论

**① 回收站入口是 `{drivews}/moveItemsToTrash`（POST）。**
前两轮在 docws 上找 `trash` 模块全部 404，因为它根本不在 docws 上。

```json
{"items":[{"drivewsid":"...","etag":"...","clientId":"..."}]}
```

**② 写接口全部要带 `etag`。** `renameItems` / `moveItems` / `moveItemsToTrash`
的 body 里每个 item 都含 `etag` —— 这是 **乐观并发控制**。
tag 不对时服务端会拒绝，所以本地必须每次写前先拿到最新 etag。

**③ `lookup_by_path` 的 body 是一个 JSON 字符串，不是对象。**

```json
"/Documents"
```

而 `unified_format=true` / `false` 返回**两套完全不同的字段结构**：

| 格式 | 返回形态 | 典型字段 |
| --- | --- | --- |
| `true` | 与 `retrieveItemDetails` 同构 | `drivewsid` / `zone` / `type` / `etag` |
| `false` | 文档层视图 | `document_id` / `mtime` / `ctime` / `btime` / `size` / `deleted` |

**④ `update/documents` 必填 `command`。** 传 `{}` 得到
`Unexpected input at [line: 1, column: 2]`；传 `{"command":"add_file",…}` 则 200。

**⑤ 写接口的 body 形状不对称，易错：**

| 端点 | body |
| --- | --- |
| `retrieveItemDetails` | `{"items":[…]}` 包裹 |
| `retrieveItemDetailsInFolders` | 裸数组 |
| `lookup_by_path` | **裸字符串** |
| `createFolders` | `{destinationDrivewsId, folders:[…]}` |
| 其余写接口 | `{items:[…]}` |

> [!CAUTION]
> **`/v1/item/copy/{id}` 会真的复制文件。** 本想用它探明“缺字段会怎么报错”，
> 结果它成功了并且在 `/Documents` 里创建了一份「`.localized` 2」。
> 已用 `moveItemsToTrash` 清掉（并把原 `.localized` 一并处理，
> 该文件是 macOS 的自动生成垃圾文件）。
> **做这类探针时，先假定写接口会成功，不要假定它会报错。**

#### 服务端字段的额外发现

`unified_format=false` 的返回里带了客户端能直接用的时间戳（秒这里毫秒）：

| 字段 | 含义 |
| --- | --- |
| `mtime` / `ctime` / `btime` | ms 时间戳（修改/创建/备份） |
| `size` | 目录为 `-1` |
| `deleted` | bool，**软删除标记** |
| `etag` | 形如 `npq::`（带后缀） |
| `status.status_code` | `0` 表示 OK |

> [!TIP]
> `unified_format=false` 返回的 `deleted` 字段可能比 `moveItemsToTrash` 更容易用于删除检测，
> 但本轮的回收站列表接口仍未找到 —— `moveItemsToTrash` 只能**写**，
> **读回收站内容**的接口仍缺失。

---

### 5.11 CDN 直链 Header 约束与下载通知（D4 实测结论）

#### A. CDN 直链 503 避坑
- `Session.NewRequest` 会给请求自动注入 `Origin: https://www.icloud.com` 与 `Referer: https://www.icloud.com/`，对 `cws`/`cvws` 签名下载直链会导致 **HTTP 503 Bad Gateway**。
- 下载必须通过标准的 `http.NewRequest` 构造纯净请求，不带 `Origin` / `Referer` / Cookie。

#### B. 同名文件不覆盖策略
- 本地下载采用非破坏性命名逻辑：同名文件自动累加 `name (1).ext`、`name (2).ext` 后缀，后缀保留在扩展名之前。
- 取消“先删后写”流程，防止下载失败时弄丢本地已有文件。

#### C. 下载通知与点击调起
- 使用 `icloud_drive_download` 专用通知渠道。
- 采样 `tempFile.length()` 实时刷新通知栏百分比/已下载大小。
- 下载完成通知挂载 `PendingIntent`（`Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`），点击直接唤起系统或第三方应用打开本地 `content://` 文件。

---

### 5.10 尚未验证（D5~D7 的前置）

| 问题 | 归属阶段 |
| --- | --- |
| Drive 直链的**真实寿命**（已验 206，未验多久后失效） | D4（curl 定时实测）|
| 上传令牌拿到后，字节怎么 POST、怎么提交记录 | D5 |
| **读回收站内容的接口**（写入口 `moveItemsToTrash` 已找到） | 删除感知 |
| **子文件内容变更是否 bump 父目录 `etag`** | 内容变更检测 |
| 同名文件冲突语义 | D6 |
| iWork 文档包（`.pages`）是否显示为 FILE | D2 后补验 |
| 共享文件（`share` / `shareID`）的权限模型 | D6 之后 |
