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
> D3 必须重新找 Drive 的结构增量机制。候选方向：
> 1. 根/目录列举比 `etag`（最笨但确定可用）
> 2. `ckdatabasews` 的 CloudKit 层 delta
> 3. 是否有 `retrieveItemDetails` 之外的 listing 变体
>
> **开工 D3 前必须先侦察，不要照着原计划写。**

### 5.7 尚未验证（D3~D7 的前置）

| 问题 | 归属阶段 |
| --- | --- |
| Drive 直链的真实寿命 | D4（curl `Range` 实测） |
| `/upload/web` 分片流程与 filesignature 语义 | D5 |
| 同名文件冲突语义 | D6 |
| iWork 文档包（`.pages`）是否显示为 FILE | D2 前需再验一次 |
| 共享文件（`share` / `shareID` 已在 3/41 项出现）的权限模型 | D6 之后 |
