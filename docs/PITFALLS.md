# 关键工程避坑指南 (必读注意事项)

> 本文档收录 iClouDroid 开发中**必须提前知道**的陷阱。踩过的具体案例见 [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md)。
>
> 返回 [TODO.md](TODO.md)

---

## 1. 国区（云上贵州）与国际区的严格隔离

**域名与端点**

| 区域 | 登录入口 |
| --- | --- |
| 国区 | `https://setup.icloud.com.cn` |
| 国际区 | `https://setup.icloud.com` |

**强制 Header 校验**

向苹果发送的**所有** API 请求，必须严格携带匹配的 `Origin` 和 `Referer`：

| 区域 | Origin | Referer |
| --- | --- | --- |
| 国区 | `https://www.icloud.com.cn` | `https://www.icloud.com.cn/` |
| 国际区 | `https://www.icloud.com` | `https://www.icloud.com/` |

> [!WARNING]
> 若漏传或混用 `.com` 与 `.com.cn`，苹果网关会直接返回 `421 Misdirected Request` 或 `400 Bad Request`。

---

## 2. 万级图库（10,000+ 照片/视频）的首屏性能与扫描策略

**严禁全量拉取后阻塞等待**

面对上万张照片和数千个视频的存量图库，CloudKit 的 `/records/query` 单次最多返回约 **200 条记录**。
注意：因为每张照片包含 `CPLAsset` 和 `CPLMaster` 两条记录，实际单次请求约对应 **100 张照片**。

**全库月扫描 + 边扫边看**

- 查询时按 `assetDate` 降序请求，使用 `continuationMarker` 游标推进
- 每拉取完一批就写入 `Room` 并重建对应月份的 `photo_month_index`，UI 立即可以浏览
- 游标持久化在 `scan_state`，中断后可断点续扫（下次启动不会重头开始）
- 切到后台由 `MonthScanWorker` 接管，通知栏显示常驻进度

> [!CAUTION]
> **不要用 Paging 3 的 `RemoteMediator` 做这个。** 它在每次 APPEND 成功后无条件
> invalidate `PagingSource`，重载窗口 = `initialLoadSize`，超出窗口的滚动位置被 clamp，
> 表现为深翻页跳回顶部。实测塔陷点恒等于 `initialLoadSize`（60→60、360→360、1200→1200），
> **无参数可解**。详见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)。

**滚动锚点用月份，不用全局索引**

月分组后每个月是「一个标题 + N 行」，每月行数不同。任何「每月固定 N 项」的索引换算都会错位，
必须从 item key（`h_202608` / `r_202608_3`）解析月份。

---

## 3. `downloadURL` 临时直链过期与 Coil 缓存失效问题

> 本节已有**实测数据**，详见 [ISS-026](CHANGELOG_ISSUES.md#iss-026) 与
> [url_lifetime_experiment.md](../.artifacts/url_lifetime_experiment.md)。

**问题本质（已实测，推翻旧说法）**

| 旧文档说法 | 实测结果 |
| --- | --- |
| 有效期 **1~2 小时** | ❌ 实际 **~15~18 分钟**（882s 仍 200，1065s 变 410） |
| **会话重建即失效** | ❌ 会话重建后旧 URL 仍返回 200 |

所以：**URL 寿命只由时间决定，与会话代际无关。** CDN 签名绑定的是 record。

**解决方案**

1. **自定义 Coil 缓存 Key**
   缓存主键绑定永久不变的 `recordName`，**绝不要**绑会变的 `downloadURL`：
   `icloud_{recordName}_{quality}`。

2. **按年龄主动换链**
   `URL_MAX_AGE_MS = 12 分钟`（在实测寿命内留余量）。超过就主动调
   `/records/lookup` 换新链，**不要等它变成 410**。

3. **不要用代际作为刷新依据**
   代际只能当「可能换了账号」的提示信号，不能当「URL 已死」的证据。

> [!CAUTION]
> **这类“有效期多久”的结论必须用 curl 实测，不要相信文档（包括本文）。**
> 验证方法：从 DB 取一条 URL → `curl -s -o /dev/null -w '%{http_code}' -r 0-1023 '<url>'`。
> 必须用 GET + `Range`，Apple CDN 对 `HEAD` 请求返回 `501`。

---

## 4. `gomobile` 跨语言调用 (JNI) 的限制与内存红线

**类型限制**

`gomobile bind` 导出的函数签名仅支持基础类型（`string`、`int64`、`bool`、`[]byte`）以及简单的 Go `struct` / `interface`。

> 复杂的数据结构（如照片分页列表）建议在 Go 层统一序列化为 **JSON 字符串（`string`）** 跨边界传递给 Kotlin，在 Kotlin 层用 `kotlinx.serialization` 解析，这样稳定性最高且易于调试。

**无回调函数支持**

`gomobile` **不支持**将 Go 函数指针/闭包传给 Kotlin。需要"推送"的场景（如 Token 静默刷新）只能：
- 轮询（新增 `PollUpdatedCookies()` 之类的导出函数）
- 或每次调用后返回最新状态串

**严禁使用 `[]byte` 传递大文件**

> [!CAUTION]
> 在上传或下载几十 MB 的原图或数 GB 的 4K 视频时，**绝对不能**将文件读入 `[]byte` 跨 JNI 传递，否则会瞬间触发 Android 的 `OutOfMemoryError`。
>
> 正确做法：**Kotlin 只把本地文件的绝对路径（`filePath: String`）或通过 FD 传给 Go 层，由 Go 层直接在本地文件系统中流式读写**。

---

## 5. 高级数据保护 (ADP) 与 2FA 过期保活

**ADP 网页访问开关**

如果账号开启了端到端加密的"高级数据保护"，必须在 iPhone/iPad/Mac 的「设置 → Apple ID → iCloud」中确保开启了 **"在网页上访问 iCloud 数据"**，否则所有 Web API 均会返回权限拒绝。

**2FA 信任期上限**

苹果服务端的 `X-APPLE-WEBAUTH-HSA-TRUST` 有效期硬上限为 **30~60 天**。

当后台同步捕获到 `421` 且无法通过 `trustToken` 刷新会话时，App 需：
1. 自动挂起 `WorkManager` 队列
2. 弹出高优先级系统通知："iCloud 授权已到期，请点击重新完成双重认证"

---

## 6. WebView → Go 的 Cookie 传递陷阱

> 详细案例见 [ISS-001](CHANGELOG_ISSUES.md#iss-001-cookie-值双引号未剥离导致-go-静默丢弃全部凭证)

**双引号剥离**

`CookieManager.getCookie()` 返回的 Cookie 值可能被双引号包裹（`X-APPLE-WEBAUTH-TOKEN="v=2:..."`）。
Go 的 `net/http` 遇到非法字节会**静默丢弃**该 Cookie（只打印一行警告，不返回 error），导致请求变成空 Cookie。

```go
value := strings.Trim(strings.TrimSpace(kv[1]), `"`)  // 必须
```

**大小写不敏感**

实测 WebView 返回 `X-APPLE-WEBAUTH-PCS-Cloudkit`（小写 k），而设计文档写作 `PCS-CloudKit`。所有名称匹配必须大小写不敏感。

**跨子域注入**

`cookiejar` 默认使用 `golang.org/x/net/publicsuffix`，会把 `com.cn` 视为公共后缀，**拒绝**将 Cookie 设置到父域。且 CloudKit 节点（`pXX-ckdatabasews.icloud.com.cn`）与对象存储（`cvws.icloud-content.com.cn`）属于不同子域。

> 本项目解法：放弃 `cookiejar` 自动匹配，`Session` 内部维护 `CookieMap`，每次请求显式注入 `Cookie` Header。

---

## 7. 状态管理的竞态陷阱

> 详细案例见 [ISS-005](CHANGELOG_ISSUES.md#iss-005-authviewmodel-区域取值三处推断不一致) / [ISS-006](CHANGELOG_ISSUES.md#iss-006-登录成功后-icloudwebview-重复触发-initsession)

**单一真理源**

同一份业务状态存在多个副本（UI State / Repository / VM 字段）时，必须指定唯一真理源，其余副本只能作为只读投影，禁止参与决策。

**同步哨兵位 > 异步状态机**

「状态机守卫」（`if (state is Authenticating) return`）在异步场景下不可靠 —— 状态变更发生在协程中，而回调可能在状态更新前批量到达。

> 这类场景必须用**同步的布尔哨兵位**（主线程执行，无竞态）而非 Flow/State 做幂等控制。

**Go 结构体零值陷阱**

Go 结构体的零值（`false` / `""` / `0`）与"业务合法值"无法区分。只要结果结构里带标识字段，**每个 return 分支都必须显式赋值**，或统一走构造函数。

---

## 8. 退出登录（异步清理）的写回陷阱

> 详细案例见 [ISS-025](CHANGELOG_ISSUES.md#iss-025)

退出登录不是一个瞬间动作，而是一段**异步清理窗口**。在这段窗口里，系统的其他部分仍在正常运行，
并且会把手里的旧状态重新写回去。已实测的两条写回路径：

| 写回路径 | 触发时机 | 后果 |
| --- | --- | --- |
| `MonthScanEngine` / `syncIncremental` / `UploadWorker` → `upsertAll()`<br>（历史案例中的执行者原为已删除的 `PhotoRemoteMediator`，见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)） | 飞行中的分批写入在 `clearAll()` 之后落库 | 旧账号照片行被重新写回 |
| `ICloudWebView` Cookie 探针 → `onCookiesExtracted()` → `saveSession()` | `removeAllCookies` 完成前读到旧 Cookie | 会话被原地重建，`shared_prefs` 保留凭证 |

**为什么 `AuthUiState` 挡不住**

`logout()` 第一件事就是把状态置为 `NotLoggedIn`，而探针回调里的守卫写的是
`if (state is Authenticating || state is LoggedIn) return` —— 于是 `NotLoggedIn` **恰好放行**了回调。

```
38.725 W/PhotosViewModel: checkZoneReady failed: 正在退出登录…   ← Repo 守卫拦住
38.857 I/ICloudAuth: Three-element cookie validation PASSED     ← 状态守卫放行
38.859 I/ICloudAuth: Calling Go InitSession
```

**正确做法**

1. 用 `@Volatile` 布尔位，在 `logout()` **第一行**置位，早于任何清理
2. 所有可能写回数据的异步回调入口都检查该位
3. 释放时机在“清理完成 + 页面重载完成”之后（而非 `clearAll()` 返回就释放）

```kotlin
@Volatile private var isLoggingOut = false

fun logout() {
    isLoggingOut = true                    // 第一行
    viewModelScope.launch {
        repository.beginWipe()
        try { /* 四阶段清理 */ }
        finally {
            repository.endWipe()
            delay(800)                     // 等登录页重载
            isLoggingOut = false
        }
    }
}

fun onCookiesExtracted(r: CookieValidationResult) {
    if (isLoggingOut) return               // 关键
    ...
}
```

> [!CAUTION]
> **新增任何「退出登录时需要清理的状态」，都要反问一次：它会不会被异步回调写回？**
> 只清不拦，等于没清。

**`removeAllCookies` 是异步的**

传 `null` 只是放弃回调，**并不代表已同步完成**。紧接着 `flush()` 有可能把删除前的 jar 落盘，
导致进程重启后旧 Cookie 仍然存在。必须把回调桥接成挂起点：

```kotlin
suspendCancellableCoroutine<Unit> { cont ->
    cookieManager.removeAllCookies { if (cont.isActive) cont.resume(Unit) }
}
cookieManager.flush()
```

---

## 9. Go map 迭代顺序是随机的

> 详细案例见 [ISS-026](CHANGELOG_ISSUES.md#iss-026)

Go 语言**故意**打乱 `map` 的迭代顺序（防止依赖顺序的实现）。因此任何
「遍历 map 拼出字符串 → 拿去做相等比较 / 持久化 / 下发给其他语言」的代码都是定时炸弹。

本项目踩到的真实形态：

```go
// ❌ 同一份 Cookie 集合，每次调用得到不同的字符串
for k, v := range s.CookieMap {
    b.WriteString(k); b.WriteString("="); b.WriteString(v)
}
```

Kotlin 侧拿这个字符串与持久化的比较，于是 **每次冷启动都被判定为“凭证已轮转”**。

```go
// ✅ 排序后再拼，结果确定
keys := make([]string, 0, len(s.CookieMap))
for k := range s.CookieMap { keys = append(keys, k) }
sort.Strings(keys)
```

> [!WARNING]
> 跨语言边界的字符串必须**保证确定性**。除了 map 顺序，还要提防浮点格式化、时间格式、
> JSON 字段顺序（`encoding/json` 对 struct 是稳定的，对 map 不是）。

**附带教训：短期令牌 ≠ 凭证轮转**

即使顺序稳定了，也不要把「任何 Cookie 值变化」当作「凭证轮转」。苹果每次 `/validate`
都轮换 `X-APPLE-WEBAUTH-TOKEN` / `X-APPLE-WEBAUTH-VALIDATE`，但这既不影响已签发 URL，
也不影响任何业务能力。判定凭证轮转只能基于**会产生后果的**字段变化。

---

## 9.9 空列表不会触发 Room Flow 重新发射

> **症状**：空文件夹永远显示「正在读取…」，进度条一直转。
>
> **时间**：D3 阶段（2026-11），真实账号上的「空文件夹」复现。

### 根因

代码把「首次加载完成」的判定放在了 `Flow.collect` 里：

```kotlin
observeChildren(id).collect { children ->
    state = state.copy(items = children, isInitialLoading = false)  // ← 不会执行
}
```

**Room 只在表变化时通知 Flow。** 空文件夹的 `replaceChildren` 删 0 行、插 0 行，
表根本没变，所以 Flow **不会发射第二个值**，`isInitialLoading` 永远不置 false。

有内容的文件夹看不出这个问题，因为它至少会插入几行。

### 正确写法

**网络请求的结果才是「首个答案已到达」的权威**，不是 Flow：

```kotlin
refreshJob = launch {
    try {
        repository.refreshFolder(id)
    } finally {
        // 放 finally，取消和异常都不会把转圈卡住
        state = state.copy(isInitialLoading = false)
    }
}
```

Flow 继续负责保持 `items` 最新，但不再负责判定「加载完了」。

### 两个附带坑

**① 先服务端确认，再改逻辑。**
本轮先探针确认了空文件夹的真实响应（`200` / `items: []` / `status: "OK"`），
才确定问题不在解析层。否则很容易去改一个本来正确的 mapper。

**② `catch (Exception)` 会把协程取消当错误。**
`poll` 里加了 `refreshJob?.cancel()` 后，日志里出现一片 `E/DriveRepository: JobCancellationException`。
取消是正常控制流，必须单独 `catch (e: CancellationException) { throw e }`。
否则真实的错误会被这些噪音淹没。

### 自检清单

- [ ] 任何「首次加载」标志位，是否依赖 Flow 发射？
- [ ] 置位是否在 `finally` 而不是 try 末尾？
- [ ] 空列表路径是否单独跑过？
- [ ] `catch (Exception)` 是否误吞了 `CancellationException`？

---

## 9.10 带 Origin 的请求会被 CDN 拒绝（503）

> **症状**：所有下载都报 `HTTP 503: Bad Gateway`，但拿 URL 的请求完全正常。
>
> **时间**：D4 阶段（2026-11），真机复现。

### 根因

`Session.NewRequest` 会给每个请求盖上：

```go
req.Header.Set("Origin", s.Origin)      // https://www.icloud.com
req.Header.Set("Referer", s.Referer)    // https://www.icloud.com/
```

这对 API 主机是必需的（校验来源），但对 **已签名的 CDN 直链是致命的**：
签名已在 query 里，多一个外部 Origin 反而被当成越权请求。

### 定位过程（值得复用）

1. 先确认 `download/by_id` 正常 → 排除端点问题
2. 怀疑 User-Agent → **加了 UA 仍然 503** → 排除了
3. 写探针对比多种头部组合，其中 **“完全裸请求” 返回 200 且拿到真实字节**
4. 对比探针与正式代码的差异 → 发现唯一区别是 `s.NewRequest` vs `http.NewRequest`

> [!IMPORTANT]
> **“裸请求能过”是最强的线索。** 它直接指向“你发了不该发的头”，
> 比逐个试头部快得多。以后碰到 CDN 类 4xx/5xx，先试裸请求。

### 修法与自检

```go
// ✓ 下载路径用标准库构造，不带 Cookie/Origin/Referer
req, _ := http.NewRequest(http.MethodGet, signedURL, nil)
```

- [ ] 这个请求是否需要会话身份？不需要就不应该用 `s.NewRequest`
- [ ] 有没有单元测试断言“不该出现的头确实没出现”？

---

## 9.11 下载落盘不要“先删后写”

> **时间**：D4 阶段（2026-11）。

### 问题

为了让重复下载不产生 `(1)` 副本，最初的实现是：

```kotlin
resolver.delete(旧的同名行)   // ①
val uri = resolver.insert(新行) // ②
```

这有两个危害：

1. **②失败 = 旧文件也没了**。用户原本好好的文档，既没更新成功也没了。
2. **静默覆盖本地修改**。用户编辑后再下载，改动无声消失。

### 正确做法：永不删除，只追加

```kotlin
val finalName = uniqueDisplayName(...)  // report.pdf -> report (1).pdf
resolver.insert(finalName, ...)         // 新开一行
```

### 两个容易写错的细节

**① 后缀要在扩展名之前。**
`report.pdf (1)` 会丢掉文件类型，系统不再为它提供 PDF 打开方式。

**② 不要依赖 MediaStore 的去重行为。**
平台确实会给重名文件加 `(1)`，但**这不是 API 契约**，不同 OEM、不同 provider 的行为并不一致。自己算后缀才是确定的。

### 自检清单

- [ ] 任何“保存到本地”的操作，失败时会不会弄丢已存在的文件？
- [ ] 删除是否发生在写入**之前**？
- [ ] 重名处理是自己算的还是托给平台的？

---

## 10. 惰性布局嵌套（LazyColumn 内嵌 LazyVerticalGrid）

> 详细案例见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)

画廊最初把每个月做成一个嵌套的 `LazyVerticalGrid`，放在外层 `LazyColumn` 里。
为了让它能被测量，必须给它一个显式高度：

```kotlin
// ❌ 内层惰性布局拥有自己的测量循环
LazyColumn {
    items(months) { month ->
        LazyVerticalGrid(
            modifier = Modifier.height(estimateHeight(month.itemCount, columns)),
            userScrollEnabled = false
        ) { items(month.photos) { ... } }
    }
}
```

正常滚动时看不出问题。**快速 fling 之后**内层网格只量出了行首一行、其余折叠，
表现为「月份标题出现了，但内容不加载，只有最上面一个月有图」。

原因是：两个惰性布局各自有独立的测量/回收循环，外层在 fling 时以极快速度重组，
内层的测量结果来不及跟上，最终提交的是不完整的布局。

```kotlin
// ✅ 内容展开为外层的直接子项，虚拟化只发生在一个地方
LazyColumn {
    monthIndex.forEach { month ->
        stickyHeader(key = "h_${month.yearMonth}") { MonthHeader(month) }
        items(count = rowsFor(month.itemCount, columns), key = { "r_${month.yearMonth}_$it" }) { row ->
            MonthRow(photos = monthPhotos[month.yearMonth].orEmpty(), row = row, columns = columns)
        }
    }
}
```

`MonthRow` 内部用 `Row` + `Modifier.weight(1f)` 均分宽度，
`PhotoGridItem` 自带的 `aspectRatio(1f)` 保证单元格为正方形。

> [!CAUTION]
> **不要在 `LazyColumn` / `LazyRow` 内嵌套任何 `Lazy*Grid`。**
> 需要网格时按行发出 item，用 `Row` 排布固定数量的格子。
>
> 另外：`stickyHeader` 与 `items` 的 key 必须能反推出所属分组（如 `h_202608` / `r_202608_3`）。
> 滚动锚点、导航跳转都依赖这一点 —— 不能再用「每月固定 N 项」的假设去算索引。

**验证技巧**：用截图体积作客观判据。空白/异常状态截图通常 < 600KB，
正常渲染（含图片）则 > 1.5MB。比肉眼比对比对更可靠。

---

## 11. 派生状态不要作为参数传递

> 详细案例见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)

`photo_month_index.isComplete` 表示「全库扫描是否已走完，该月不会再增长」。
最初的写法是由调用方算出这个值并传进重建函数：

```kotlin
// ❌ 三个地方各自写 isComplete，互相覆盖
private suspend fun refreshMonthIndex(yearMonth: Int, isComplete: Boolean)

// 扫描中每批次：写 false
for (ym in touched) refreshMonthIndex(ym, isComplete = false)
// 扫描结束：写 true
monthIndexDao.upsertAll(months.map { it.copy(isComplete = true) })
// 冷启动重建：又写回 false
for (ym in all) refreshMonthIndex(ym, isComplete = false)
```

后果：扫描已经完成并且报过「扫描完成，共 15936 项」，但**每个月标题仍然显示「· 扫描中」**。

```kotlin
// ✅ 每次从单一真理源读取，与调用者无关
private suspend fun refreshMonthIndex(yearMonth: Int) {
    val walkFinished = scanStateDao.get(ScanStateEntity.KEY_MONTH_SCAN)?.isComplete == true
    monthIndexDao.upsert(PhotoMonthIndexEntity(..., isComplete = walkFinished))
}
```

> [!IMPORTANT]
> **「派生状态」只能有一个写入路径。** 只要它经由参数传递，就必然会在某个调用点被覆盖。
> 让读取方每次都从真理源取，无论重建多少次结果都一致。

---

## 12. 判断「是否需要初始化」不要看条数

> 详细案例见 [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)

启动时自动扫描的触发条件原本是「本地照片数为 0」：

```kotlin
// ❌ 升级安装有行但未分桶，count != 0，初始化被跳过
repository.totalCountFlow.collect { count ->
    if (count == 0 && !hasAutoStarted) { hasAutoStarted = true; startInitialScan() }
}
```

从 v3 升级到 v4 时，`photo_assets` 有 164 行、`yearMonth` 全为 0、`photo_month_index` 为空。
条件为假 → 扫描不启动 → 转年索引永远为空 → 界面显示「已加载 164 项」但主体是「暂无照片」。

**正确做法**：判断「是否存在未处理行」（`countUnbucketed() > 0`），而不是总行数。

> [!WARNING]
> 任何「本地数量为 0 就去拉取」的初始化条件，在 schema 升级、部分失败、
> 账号切换等场景下都会失效。应该判断「数据是否已达到可用状态」，而非「是否存在」。

---

## 13. 一次性返回值不等于对象状态

> 详细案例见 [ISS-029](CHANGELOG_ISSUES.md#iss-029-initsession-未将握手结果回写至-session导致进程内调用拼出空-host)

`InitSession` 调用 `ValidateSession()` 拿到一个 `SessionInitResult`，直接序列化返回，
**从未把它同步到长期存活的 `Session` 对象**：

```go
// ❌ 返回值有了，对象还是空的
result, _ := session.ValidateSession()
return marshal(result)   // session.DSID / session.CKDatabaseURL 仍为零值
```

于是 `photosBaseURL()` 读 `s.CKDatabaseURL` 时拿到空串，拼出：

```
Post "/database/1/.../records/query?...&dsid=&...": unsupported protocol scheme ""
```

**为何多年没爆**：Kotlin 层读的是返回值，自己存进加密存储。主流程里没人依赖
`Session` 内部的字段 —— 直到有一次**进程内直接读数据**的调用出现。

> [!IMPORTANT]
> 任何「调用 → 得到一次性结果对象」的设计，必须明确：谁是真理源？
> 如果后续代码会读长期对象，就必须在每次成功调用后把结果**回写**进去。
> 否则错误会在离现场很远的地方、以“空字符串”的形式出现，极难定位。

---

## 14. `/records/query` 的 `resultsLimit` 不能传 1

CloudKit 会直接报错：

```
400 BAD_REQUEST: Result size limit must be greater than 1
```

写测试或探针时很容易用 `resultsLimit: 1` “只取一条”，结果是全线失败。
最小传 **2**，取回后在客户端自行截取。

---

## 15. 探针要跑在进程内，且要避让启动竞争

验证 CloudKit 字段时需要在 **App 进程内**调用（因为 cookie 在加密存储里，外部脚本拿不到）。
但直接放在 `Application.onCreate` 里会和主流程会话初始化**抢同一个全局 `currentSession`**，
表现为：`initSession` 明明返回了正确路由，探针却读到空值。

```kotlin
// ❌ 直接跟启动流程抢
appScope.launch { Icloudcore.initSession(...); Icloudcore.probeXXX() }

// ✅ 等主流程稳定后再跑
appScope.launch { Icloudcore.initSession(...); delay(6_000); Icloudcore.probeXXX() }
```

更稳妥的做法是让探针**自带自愈**：发现 `DSID` / `CKDatabaseURL` 为空就自己重新握手一次。

> [!TIP]
> 探针代码必须**用完即删**，且用独立文件（如 `probe.go`）+ 编译期 `if (BuildConfig.DEBUG)` 包裹，
> 避免混入发布包。探针得到的结论写进文档，代码不保留。

---

## 16. `desiredKeys` 是白名单，少一个字段就少一条分支

CloudKit **只返回 `desiredKeys` 里列出的字段**。少写一个，代码里对应的判断就永远走不到，
而且**没有任何报错**——表现为“逻辑看着没错，就是不生效”。

最内伤的例子：`defaultDesiredKeys` 包含了 `isHidden`，却漏了 `isDeleted`，
于是 `rec.Fields["isDeleted"].AsInt64() == 1` 恒为 false，
“最近删除”的照片被当成正常照片入库（[ISS-030](CHANGELOG_ISSUES.md#iss-030)）。

| 教训 | 做法 |
| --- | --- |
| 新增“依据某字段过滤”的行为 | 先确认该字段在 `desiredKeys` 里 |
| 新增一个 Go 侧结构化字段 | 同步检查 `desiredKeys` + `PhotoItem` + Kotlin 解析三处 |
| 怀疑字段没生效 | 在 Go 侧打一行日志验证字段是否为空，别在 Kotlin 层猜 |

---

## 17. CloudKit 的“删除”是两段式，tombstone 只在第二段出现

| 阶段 | `CPLAsset` 状态 | `/records/query` | `/records/changes` |
| --- | --- | --- | --- |
| 移入「最近删除」 | `isDeleted = 1`，记录**还在** | 通过 `withoutHiddenOrDeleted` 视图过滤掉 | **无变更**（不是 tombstone） |
| 30 天后 expunge | 记录真正消失 | 不再返回 | 给出 tombstone |

所以：

1. **不能靠增量同步发现“移入回收站”**。必须显式拉回收站索引。
2. 回收站期内的 `CPLMaster`（含 `downloadURL`）**仍会返回**，`parseRecords` 里
   “把未配对的 master 伪造成照片”的兼容逻辑会把它变回一张可见照片。详见 `parseRecords`。
3. 拉回收站的 recordType 是 **`CPLAssetAndMasterDeletedByExpungedDate`**，
   而 `CPLAssetDeletedByExpungedDate` 只是 `HyperionIndexCountLookup` 的计数 ID
   （用它查会得到 `404 Did not find record type`）。

> [!CAUTION]
> 排查这类问题时，依据应是**服务端错误文案**（`404 Did not find record type` /
> `400 Expected a parameter with name: smartAlbum`），而不是猜参数位置。
> 本项目为此连续错了三次。

---

## 18. “全量扫描”不等于“能证明缺失”

只做 upsert 的扫描只能回答“云端**有什么**”，回答不了“云端**没有什么**”。
后者必须靠“本次遍历到的全量 ID 集合”做差集（reconciliation）。

本项目为此踩了两层坑（[ISS-031](CHANGELOG_ISSUES.md#iss-031)）：

1. 扫描只写不删 → 已永久删除的照片永久残留；
2. 加上对账后，**断点续扫把“本轮”当成了“全集”**，误删 700 张真照片。

### 18.1 带游标的任务：“走完了吗”必须是显式返回值

```kotlin
// ❌ 函数正常返回 ≠ 遍历完整
return scanned

// ✅ 把“为什么退出”显式化，只有一个赋值点是 true
data class ScanRunResult(val scanned: Int, val walkCompleted: Boolean)
```

本次唯一合法的 `walkCompleted = true` 赋值点：云端不再给 `continuationMarker` 那一页。
wipe / 401 / 网络放弃 / 协程取消 全部为 `false`。

### 18.2 任何“清理”动作，先问：我看到的是全集还是子集？

最典型的错误是“每次启动都清空 scratch 表”：

```kotlin
// ❌ 续扫时会把前半段的成果抹掉
repository.resetSeenIds()

// ✅ 只有全新一轮才清
if (state.lastMarker == null) repository.resetSeenIds()
```

### 18.3 安全闸门的判据要“可推导”，不要拍百分比

```kotlin
// ❌ 50% 阈值拦不住“只剩后半段”（一半恰好高于 50%）
seenIdCount >= localCount * 0.5

// ✅ 完整 walk 必然满足：每行本地记录至少贡献一个 id 到 seen
seenIdCount >= localCount
```

推导成立的前提很简单：`scan_seen_ids` 同时记录 `id` 与 `masterId`。
任何不满足该不等式的情况，都是“id 集不完整”的铁证，与原因无关。

### 18.4 “误删可恢复”要当成设计约束

对账只删本地缓存，**从不调云端写接口**。所以误删的最大代价是“重扫一遍”。
本次 700 行被误删后，清 `scan_state` 重跑全量扫描即全部回灌（15937 恢复）。

> [!TIP]
> 选方案时的判断依据：**误删一张的代价 ≫ 漏删一张的代价**。
> 所以不要因为“怕误删”而放弃对账；正确做法是把闸门做严、把误删做成可回灌。

---

## 19. 同一 `masterId` 可对应多条 `CPLAsset`

实测库中 155 行属于这种结构（编辑版本 / 重复导入 / 服务端重映射）：

```sql
SELECT id, filename, assetDate FROM photo_assets WHERE masterId='ASiWjzUxvNN81ZXosCMKLSKZYkFK';
54FBED04-…|IMG_1882.JPG|2024-10-06 22:54:15
79072B11-…|IMG_1882.JPG|2024-08-21 18:05:07
9F987577-…|IMG_1882.JPG|2024-08-09 16:11:28
```

后果：只要是 `WHERE masterId = ? LIMIT 1` 或 `UPDATE ... WHERE masterId = ?`，
就会导致“三张不同缩略图共用一组签名”、“读到随机一行”。

| 错误写法 | 正确写法 |
| --- | --- |
| `UPDATE ... WHERE masterId = :id` | `UPDATE ... WHERE id = :assetId`（逐行） |
| `getPhotoByMasterId()` + `LIMIT 1` | `getPhotosByMasterId()` 取全组，或按 assetId 精确取 |

> [!IMPORTANT]
> **主键是 `id`（assetId），不是 `masterId`。** `/records/lookup` 按 master 回答、
> Coil 缓存 Key 按 assetId——两者维度不同，回写时必须按 assetId 展开到每一行。
> 清理多余行时应保守：**只要 `masterId` 仍在云端返回，就不删。**

---

## 20. Compose 里的“新页面”不自动获得返回语义

本项目没有导航图：设置页、照片查看器都是 **overlay**（一个布尔状态 + 条件渲染）。
Compose 和 Activity 都不知道“上面又压了一层”，返回事件会直接落到 `MainActivity` ——
**表现为侧边手势直接退出应用**，而左上角箭头（自己调的 `onClose`）却是对的。

### 三条返回路径必须都接

| 路径 | 需要什么 |
| --- | --- |
| 侧边手势 | `PredictiveBackHandler`（或至少 `BackHandler`） |
| 自画返回箭头 | `onBack = { ... }` |
| 硬件 / 普通返回键 | `BackHandler` |

### 想要预测返回效果，就只用 `BackHandler` 是不够的

`BackHandler` 只在手势**结束时**触发：拖动全程面板纹丝不动，系统却已在画预览快照，
观感割裂。`PredictiveBackHandler` 会持续汇报 0..1 进度，面板就能跟随手指。

两个 handler 可以同时 `enabled = true`：同一次手势只会被其中一个消费，
预测返回不可用时后者兵底。不需要额外探测系统能力。

### 动画只用 `graphicsLayer`

```kotlin
// ❌ 改变布局尺寸 → 每帧重新测量整个列表
Modifier.offset { IntOffset(x, 0) }

// ✅ 绘制阶段平移 + 淡出
Modifier.graphicsLayer { translationX = p * travelPx; alpha = 1f - p * 0.6f }
```

### 必备工程保障：提前注册

两个 handler 都要**提前注册**，且注册位置要能访问到控制显隐的那个状态。
本项目把设置页包成 `SettingsContainer`，就是为了让 `BackHandler` 和 `settingsOpen`
处于同一个作用域，而不是从子组件往上抬回调。

---

## 21. 系统云端媒体提供方：代码全对但界面空白

这一类问题在 `feature/photos` 里永远遇不到，因为**失败发生在别的进程**。

### 21.1 平台级集成先查白名单，别急着改代码

Android 大量“平台级集成”都带 OEM / 服务端白名单。云端媒体提供方就是典型：

```
DeviceConfig: mediaprovider / storage_native_boot → allowed_cloud_providers
```

**症状**：Manifest 完全合规、`Provider Resolver Table` 能看到、
`get_cloud_provider` 也返回我们的 authority，但系统设置列表里就是没有。

先查这个，再改代码：

```bash
dumpsys activity provider .../MediaProvider | grep allowedCloudProviderPackages
```

### 21.2 日志要看“对方进程”的

自己的 provider 日志显示：

```
openPreview OK bytes=83966 elapsed=1ms    ← 完全正常
```

而屏幕依旧空白。真正的失败在 PhotoPicker 进程里：

```
RuntimeException: setDataSource failed: status = 0xFFFFFFEA
    at com.bumptech.glide.load.resource.bitmap.VideoDecoder.decode
```

**教训：只要“看到了图但看不到缩略图”，就去 grep 对方进程的 Glide tag，
而不是只盯着自己进程的成功日志。**

### 21.3 取消 ≠ 失败，也不能等用户“下次再来”

实测：PhotoPicker 对宫格格子**只请求一次**。

| 朴素假设 | 实测真相 |
| --- | --- |
| 失败后会重试 | 宫格不会（除非行被回收后又滚回来）|
| `CancellationSignal` 触发就该停手 | 停了就永久空白，**已下字节也不该删** |
| 超时返回 null 比阻塞好 | 空白格比等 1 秒严重得多 |

### 21.4 MIME 既决定分类也决定解码器（视频行 `onOpenPreview` 必须返回视频容器）

`MIME_TYPE` 在契约里同时被用来**给条目分类**（照片/视频、时长角标、筛选）和**给格子选解码器**。
对照片两者一致；对视频它们直接冲突：

| 报什么 | 分类 | 格子 |
| --- | --- | --- |
| `video/mp4` | ✅ 视频 | ❌ `VideoDecoder` 去解我们返回的 JPEG，`setDataSource failed: 0xFFFFFFEA` |
| `image/jpeg` | ❌ 变成照片 | ✅ 能解 |

**正确做法是把两件事拆到各自的字段上**：`MIME_TYPE` 报**真实媒体类型**（`video/*`），
由 `onOpenPreview` 履行契约另一半 —— 视频行返回**真实视频容器**字节，`VideoDecoder` 才有帧可抓。
切勿为了迁就 JPEG 缩略图而篡改 `MIME_TYPE`（那正是“视频被显示为图片”的成因）。
详见 [AGENTS §6.7.3](AGENTS.md#673-mime-既决定分类也决定解码器两者对视频冲突)。

### 21.5 元数据修正必须 bump 版本号

系统按 `LAST_MEDIA_SYNC_GENERATION` 缓存行，值不变就**不重新查询**。
改了列取值规则却不 bump，已安装设备永远看到旧数据（开发机上看不到，因为 `pm clear` 过）。

## 22. CloudKit 增量变更包缺失 `CPLMaster` 导致 Master 属性被冲刷覆写

**现象**：照片在 App/Web/iOS 点赞/收藏后，刷新本地数据发现 `LIVE_PHOTO` 媒体类型退化为 `IMAGE`，`videoComplUrl` 丢失。
**根因**：CloudKit 增量同步 (`/records/changes`) 仅返回变动的 `CPLAsset`，缺省 `CPLMaster`。若 Go 侧将 `MediaType` 默认写死为 `"IMAGE"`，且 Kotlin 侧使用 Room `@Insert(onConflict = OnConflictStrategy.REPLACE)`，会将 SQLite 中原本正确的 `mediaType == "LIVE_PHOTO"` 和 `videoComplUrl` 用默认的 `"IMAGE"` 和 `null` 覆盖改写！
**规避措施**：
1. Go 解析时，若 `CPLMaster` 缺失，`MediaType` 留空 `""`，表明 Master 级字段未在此增量包中。
2. Kotlin 侧在 `PhotoDao.upsertAll()` 之前执行 `mergeWithExistingAssets()`：如果增量更新缺失 Master 属性（如 `mediaType` 为空/`"IMAGE"` 且 `videoComplUrl` 为空），而 DB 已有记录为 `LIVE_PHOTO` / `VIDEO` 且持有 `videoComplUrl`，则强行保留 DB 中的 Master 属性。详见 [ISS-036](CHANGELOG_ISSUES.md#iss-036-收藏操作导致-live_photo-媒体类型被冲刷覆写为-image)。

## 23. 实况照片保存：拆分双文件在 MediaStore 中暴露为两条独立记录、EXIF 丢失与方向倒置

**现象**：
1. 下载 Live Photo 后，系统相册并排出现“一张照片 + 一段 2 秒视频”。
2. 未预览前下载变静态图。
3. 照片详细信息面板中丢失相机型号、快门、ISO、GPS。
4. 静态图颠倒/侧躺，而实况视频正向。

**根因**：
1. 分别向 `MediaStore.Images` 与 `MediaStore.Video` 插入两条记录，Android 视其为独立文件。
2. 直链刷新没带 `force = true`，且依赖 UI 触发 `refreshUrlsForMaster` 导致未预览时 `videoComplUrl` 为空。
3. 转码/解码 Bitmap 剥离了 Exif 属性，且没有根据 Exif `Orientation` 物理旋转 `Bitmap` 像素。

**规避措施**：
1. **单文件 Motion Photo 打包**：使用 `packJpegMotionPhoto()` 在 JPEG APP1 插入 Google Motion Photo XMP (`GCamera:MotionPhoto="1"`, `GCamera:MicroVideoOffset`)，将 `.MOV` 视频补充包挂载在 JPEG 尾部，仅向 `MediaStore.Images` 写入唯一一条记录。
2. **强制刷新**：下载 Live Photo 时若 `videoComplUrl` 为空，强制调用 `repository.refreshUrlsForMaster(masterId, force = true)`。
3. **EXIF 复制与物理旋转**：解析原图 `TAG_ORIENTATION` 并通过 `Matrix().postRotate()` 将静态图像素物理旋转至正向，重置 `Orientation = 1`；通过 `ExifInterface` 将原图的所有 Exif 字段全量写回产物文件中。详见 [ISS-037](CHANGELOG_ISSUES.md#iss-037-实况照片单文件-motion-photo-打包与-exif-防旋转对齐)。

## 24. 画廊网格加载卡顿：SubcomposeAsyncImage、单条 DB 写入事务与实时坐标重算

**现象**：画廊上下滑动或时间胶囊跳转远端月份时，主线程严重卡顿、频发 `Choreographer: Skipped 150 frames!` 掉帧，甚至缩略图变灰停止加载。

**根因**：
1. **SubcomposeAsyncImage 双重测量**：`PhotoGridItem` 使用 `SubcomposeAsyncImage` 产生嵌套子组合，单元格每帧执行双重测量与布局。
2. **`onGloballyPositioned` 实时生成 Rect 对象**：滑动时每帧对几十张卡片调用 `coords.boundsInWindow()`，触发海量 `Rect` 内存分配与坐标重算。
3. **逐条数据库更新与 Flow 轰炸**：滑入新月份时，每张照片独立刷新直链并单独执行 `photoDao.updateUrls` 写入。Room 的 `InvalidationTracker` 在 1~2 秒内触发 20~30 次 Flow 重新发射与 UI 强制 Recompose。
4. **`monthDaysMap` 全局重算**：每当有 1 张照片直链更新，ViewModel 就会对已订阅的所有月份重新执行 `DayBucketing.groupPhotosByDay(...)`。
5. **`SessionManager` 加密磁盘偏好锁开销**：拦截器每张图都读 `EncryptedSharedPreferences.getInt()`。

**规避措施**：
1. **`AsyncImage` 替换 `SubcomposeAsyncImage`**：避免子组合测量开销，占位由卡片背景色优雅兜底。
2. **坐标收集惰性求值**：`ThumbnailBoundsTracker` 使用 `HashMap<String, LayoutCoordinates>`，仅在用户点击大图查看时才计算 `boundsInWindow()`。
3. **数据库事务化与月份级批量预热**：`PhotoRepository.refreshUrlsForMasters` 内使用 `database.withTransaction { ... }` 将批量写入压缩为 1 次原子提交；在 `PhotosViewModel.observeMonth` 中进行月份级批量预热。
4. **日分组增量计算**：`monthDaysMap` 仅对数据变动的特定月份重新计算 `groupPhotosByDay`。
5. **Session Generation 内存缓存**：`sessionManager` 增加 `@Volatile` 内存缓存，消除偏好文件读取锁开销。
详见 [ISS-046](CHANGELOG_ISSUES.md#iss-046画廊缩略图加载主线程掉帧与滑动胶囊跳转重绘轰炸治理)。

---

## 25. Kotlin 协程与 JNI 阻塞调用的取消脱节（取消伪生效）

> 详细案例见 [ISS-047](CHANGELOG_ISSUES.md#iss-047drive-下载取消按钮伪取消jni-阻塞脱节与文件名无后缀显示修复)

**现象**：
在 UI 或通知栏点击“取消下载”按钮后，通知栏和 UI 上的进度条停止更新（因为 Kotlin 侧的 `tickerJob` 被取消了），但底层 Go JNI 阻塞调用仍然在后台默默将整个大文件完整地下载并落盘。

**根因**：
1. Kotlin 协程取消（`job.cancel()`）会将协程状态设置为 `cancelling`；
2. 但底层 `Icloudcore.downloadToFileWithID` 正阻塞在 Go 原生 JNI 的 `io.Copy(f, resp.Body)` 阻塞循环中，**JNI 原生 C/Go 函数无法自动感知 Kotlin 协程的 `CancellationException`**；
3. 原先在 Kotlin 侧注册的 `invokeOnCompletion` 回调，根据 Kotlin 协程语义**只在 Job 彻底 Completed（即 JNI 原生函数执行完毕返回后）才会触发**，所以回调触发时文件其实已经下载完成了。

**正确做法**：
1. **Go 侧暴露基于 Context Cancellation 的原生取消函数**（如 `CancelDownload(id)`）；
2. **在取消触发点（取消按钮点击 / 通知栏广播接收器）同步直接调用 Go 原生取消函数**（`Icloudcore.cancelDownload(id)`），而不是仅仅靠 `job.cancel()`；
3. Go 侧使用 `http.NewRequestWithContext(ctx, ...)`，触发 `cancel()` 时 Go 原生网络读写会**瞬间强制关闭底层 TCP Socket 连接**，毫秒级终止网络传输。


