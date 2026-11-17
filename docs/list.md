# list.md — 未修复问题清单

> 本文件只保留**尚未修复**的问题与持续性工程注意事项。
>
> 已修复案例请见 [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md)（ISS-001 ~ ISS-075）。
>
> 相关文档：[TODO.md](TODO.md)（路线图）、[CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md)（已修复归档）

---

## 索引

> [!NOTE]
> 当前**暂无未修复项**。修完的问题一律按 [AGENTS.md §11](AGENTS.md#11-问题归档与文档维护) 迁入
> [CHANGELOG_ISSUES.md](CHANGELOG_ISSUES.md)，本表只登记「尚未修复」的条目。

| 编号 | 标题 | 优先级 | 归属 | 状态 |
| --- | --- | --- | --- | --- |
| — | 暂无未修复问题 | — | — | — |

### 已归档至 CHANGELOG_ISSUES.md

| 原编号 | 归档位置 |
| --- | --- |
| P1 APP 多语言支持评估与全面解耦 | [ISS-075](CHANGELOG_ISSUES.md#iss-075app多语言i18n全面解耦与-android-13-系统级语言偏好无缝接入) |
| P2-001 `isAuthError` 误判 | [ISS-015](CHANGELOG_ISSUES.md#iss-015) |
| P2-002 / P2-004 / P2-007 URL 重取链路 | [ISS-017](CHANGELOG_ISSUES.md#iss-017) |
| P2-003 / P2-006 / P2-009 滚动状态与日志治理 | [ISS-018](CHANGELOG_ISSUES.md#iss-018) |
| P2-005 / P2-008 会话代际与刷新洪泛 | [ISS-016](CHANGELOG_ISSUES.md#iss-016) |
| P2-010 / P2-011 HDR 高位深与去重误伤 | [ISS-023](CHANGELOG_ISSUES.md#iss-023) |
| P2-012 `append` 指示器导致跳顶 | [ISS-019](CHANGELOG_ISSUES.md#iss-019) |
| P2-013 冷启动主线程阻塞 | [ISS-022](CHANGELOG_ISSUES.md#iss-022) |
| P2-014 `continuationMarker` 死循环 | [ISS-020](CHANGELOG_ISSUES.md#iss-020) |
| P2-015 Go 层缺结构化错误字段 | [ISS-028](CHANGELOG_ISSUES.md#iss-028-go-层缺结构化错误字段导致-kotlin-靠正则猜) |
| P2-016 `RemoteMediator` 深翻页跳顶 | [ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治) |
| P2-017 `time.Now()` 排序键漂移 | [ISS-024](CHANGELOG_ISSUES.md#iss-024) |
| P2-018 退出登录不清洗数据层 | [ISS-025](CHANGELOG_ISSUES.md#iss-025) |
| P2-019 Cookie 顺序抖动与 URL 失效判定 | [ISS-026](CHANGELOG_ISSUES.md#iss-026) |
| P2-020 月份轻封面缩略图空白（四层断链） | [ISS-038](CHANGELOG_ISSUES.md#iss-038-年月跳转小窗月份轻封面缩略图空白四层断链修复) |

---

## 附：通用注意事项

### 1. 排查 401/403/410 问题的正确姿势

| 做法 | 评价 |
| --- | --- |
| ❌ `jsonStr.contains("401")` | 会被 base64 误伤，这是 ISS-015 的成因 |
| ❌ 把「会话重建」当作 URL 失效的证据 | 已证伪：实测 gen=6 签发的 URL 在会话重建到 gen=7 后仍返回 `200`，代际只是辅助提示（ISS-026） |
| ✅ 按 `fetchedAt` **年龄**判定（`URL_MAX_AGE_MS` = 12 分钟）+ 捕获真实的 HTTP 403/410 | URL 过期由**时间**与**真实失败**驱动，与会话重建无关 |
| ✅ 解析 JSON 的 `errorCode` / `httpStatus` 字段 | 结构化、可靠（ISS-028 后 Go 层直接提供） |
| ✅ 直接读 `HttpDataSource.InvalidResponseCodeException.responseCode` | 事实来源 |

### 2. 会话重建是"昂贵操作"（但不会作废 URL）

一次重建 = 一次 `/validate` 网络往返（1~2 秒）+（仅在 `force = true` 恢复路径下）一次 generation 递增。

**已签发 URL 不会因会话重建失效** —— 实测 URL 跨代际依然返回 `200`，寿命仅由时间决定
（15~18 分钟，阈值设为 12 分钟）。但频繁重建仍会严重拖慢请求并放大限流风险。

**任何触发重建的路径都必须极度保守** —— 宁可提示用户"网络错误，请重试"，
也不要贸然重建会话。特别地：**`TIMEOUT` / `RATE_LIMITED` / `NETWORK` 一律退避重试，不重建。**

> 验证方法：拿 DB 里的旧 URL 直接 `curl`（必须 GET + `Range`，Apple CDN 对 `HEAD` 返回 `501`）。
> 返回 `200` 说明这条 URL 仍然有效，重建属误判；只有 `403` / `410` 才是真失效。

### 3. 惰性布局不要嵌套

`LazyColumn` 内嵌 `LazyVerticalGrid` 时，内层必须给定固定高度，因而拥有自己的测量循环。
外层快速滚动时两者会失去同步，内层只量出行首一行、其余折叠（ISS-027）。

正确做法：把内容展开成外层的直接子项。需要网格时，按行发出 item，每行内用 `Row` 排布固定数量的格子。

### 4. 高位深图片的兼容性

Apple 的 HDR 截图/照片可能是 16-bit PNG 或 10-bit HEVC（`heix`）。
Android `BitmapFactory` 与 `ImageDecoder` 都无法解码，必须降级到服务端转码的
8-bit JPEG 中间图。详见 [ISS-023](CHANGELOG_ISSUES.md#iss-023)。

### 5. Go 层排序键必须稳定

任何在数据获取阶段生成的"当前时间"都会让同一实体在不同次查询中得到不同排序，
表现为列表项无序漂移。缺失时间戳时应使用中性值（如 `0`）。详见
[ISS-024](CHANGELOG_ISSUES.md#iss-024)。

### 6. 派生状态不要作为参数传递

`isComplete` 这类"由甲计算、由乙写入、由丙读取"的状态，一旦经由参数传递就必然被覆盖。
每次从单一真理源重新读取，无论重建多少次结果都一致。详见
[ISS-027](CHANGELOG_ISSUES.md#iss-027-paging-3-remotemediator-导致深翻页跳顶改用月分组本地分页根治)。

### 7. 判断「是否需要初始化」不要看条数

升级安装有数据但未处理（如未分桶），`count == 0` 为假，初始化被跳过 ——
表现为"有数据但看不见"。判断依据应是"是否存在未处理行"，而非总行数。
