# AClouDroid

> **在 Android 手机上直接浏览与同步你的 iCloud 。** 不需要 iPhone，不需要 iPad，也不需要开电脑。

如果你的主力机是安卓设备，或有一两个安卓副机，但照片都存在 iCloud 里。
**AClouDroid** 能让你在 Android 上流畅浏览 iCloud 中的照片、视频与文件，将本地相册自动备份到 iCloud，甚至可以让其他 Android App 也能直接选择 iCloud 里的照片。

---

## ✨ 它能做什么

**📷 浏览你的 iCloud**
登录你的 Apple ID，即可在 Android 上查看你 iCloud 中的全部数据。可以查看照片库、云盘、备忘录等等

**🔄 本地相册自动备份至 iCloud（含实况照片）**
支持将本地相册（照片、视频、Google Motion Photo）自动后台备份上传至 iCloud。
自动识别 Motion Photo（安卓动态照片） ，并以 Apple 原生 **Live Photo** 格式无损写入 iCloud Photo；具备本地指纹与云端双重查重，避免重复上传。

**📸 实况照片 (Live Photo) 双向互转** *（不保证对所有机型有效）*
- **下载**：iCloud 里的 Live Photo 保存到手机时自动打包为标准的 Google Motion Photo
- **上传**：本地 Motion Photo 上传时自动解析并转换为 Apple Live Photo。

**📱 集成进系统 PhotoPicker（云端媒体提供方）**
支持 Android 13+ 的 `CloudMediaProvider` 标准。在浏览器、X、小红书等第三方 App 选图时，可以直接选择 iCloud 里的照片与视频

**🖼️ 全屏预览、读取 EXIF 元数据**
全屏预览支持照片、视频以及 Live Photo。info 面板支持解析与展示云端照片的 拍摄设备、GPS 数据等信息。

**🎨 原生 Android UI**
采用 Material 3 风格设计，支持 Android 12+ Monet 动态取色。

---

## 🚀 开始使用

1. **安装并打开 App**：选择你的账号区域（中国大陆 / 国际区），并进行登录。*记得勾选「信任此浏览器」*
3. **提取cookie**：在 cookie 提起诊断的下拉菜单中点击“提取 cookie”
4. **开始浏览与同步**：Enjoy it！
5. **可选：激活系统云端媒体提供方**：进入「APP设置 -> 系统整合」，可通过 Shizuku 授权激活云端媒体提供方，让其他 App 选图时直接调用 iCloud 照片。

---

## ❓ 常见问题

**Q：我的密码会被上传或泄露吗？**  
不会。登录完全通过本地 WebView 页面进行（和浏览器登录 `icloud.com` 完全一致）。App 仅加密保存登录成功后由 Apple 颁发的会话凭证（Cookies/Tokens）。

**Q：为什么第一次整理图库要花一些时间？**   
读取数据需要一定时间，你可以先行浏览已读取的数据。APP 支持后台扫描，你可以待扫描完所有数据后再返回查看

**Q：在第三方 App 选图时怎么直接用 iCloud 照片？**   
Android 13+ 支持系统级的 PhotoPicker 云端扩展。在 iClouDroid 的「设置 -> 系统整合」中，可找到该选项

**Q：本地相册自动备份是怎么工作的？**   
在「设置 -> 自动备份」中开启备份功能并勾选需要备份的本地文件夹。备份服务会在后台检测新照片，进行去重校验后上传至 iCloud，支持照片、视频与 Motion Photo。

**Q：退出登录后，本地数据会留存吗？**   
不会。退出登录后会抹除所有数据

---

## 📊 目前的进度

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| iCloud 登录 | ✅ 已可用 | 国区 / 国际区 |
| 浏览照片与视频 | ✅ 已可用 | 日视图 / 月视图 |
| 月分组视图 + 年月快速跳转 | ✅ 已可用 | 悬浮胶囊 + 3×4 月份封面 |
| EXIF 元数据查看 | ✅ 已可用 | 机型/曝光/焦距/GPS 等数据显示 |
| 云端 Live Photo 保存到手机相册| ✅ 已可用 | 自动转换为 Google Motion Photo |
| 系统 PhotoPicker 集成 (CloudMediaProvider) | ✅ 已可用 | Android 13+ |
| 本地相册自动备份上传至 iCloud | ✅ 已可用 | 支持本地相册增量上传，支持上传动态照片 |
| 定时周期扫描与全量对账 | ✅ 已可用 | 自动感知云端删除与回收站状态 |
| 动态 Monet 取色与深/浅色外观 | ✅ 已可用 | Material 3 风格 UI |
| iCloud Drive 文件查看、上传与下载 | ✅ 已可用 |支持上传下载、新建、删除、重命名、移动文件。Pad端支持查看 iWork 创建的文档|
|备忘录、提醒事项……|计划中|

---

## 📌 注意事项

- **链接时效**：iCloud 生成的直链有效期约为 15~18 分钟。App 内置了自动刷链与重试机制；若偶遇图片加载失败，再次点击或刷新即可恢复。
- **项目更新同步**：你在 iPhone 或 Mac 上新拍的照片或上传的新文件，需待其上传至 iCloud 并在 AClouDroid 触发增量同步后呈现。
- **后台备份电池优化**：如开启了本地相册自动备份，建议在系统设置中将 AClouDroid 的电池优化设置为「不受限制」，以保障后台备份正常运行。


## 🤝 致谢

本项目在开发过程中参考了以下开源项目：

- [rclone/rclone](https://github.com/rclone/rclone) —— HTTP 会话与网络通信底座
- [icloud-photos-downloader/icloud_photos_downloader](https://github.com/icloud-photos-downloader/icloud_photos_downloader) —— CloudKit 协议结构与字段对照参考

---

> **免责声明**：本项目为个人开发的第三方开源客户端，与 Apple Inc. 无任何关联。
> 使用前请确保你了解并遵守 iCloud 的使用条款，并自行承担账号与数据相关风险。
