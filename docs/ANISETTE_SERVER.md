# 用自己的电脑生成设备指纹（大文件上传）

云上贵州账号上传超过 1 GB 的文件时，AClouDroid 会走苹果设备的原生上传方式。这条路每次登录、每次上传前，都要一份**新鲜的苹果设备指纹**（anisette，约 2 分钟就过期）。

指纹只有苹果自己的代码才能算。App 默认向公开服务器 `new.ani.sidestore.io` 要。如果你不想依赖别人的服务器，可以在**自己的 Mac 或 Windows 电脑**上跑一个小程序，让安装了 AClouDroid 的 Android 设备直接找你的电脑要。

| | 公开服务器（默认） | 自己的电脑 |
|---|---|---|
| 要开着电脑吗 | 不用 | 要，上传时电脑要开机、联网 |
| 对方能看到什么 | 你的 IP 和请求时间 | 没有第三方 |
| Apple ID 设备列表 | 多一台很多人共用的虚拟 Mac | Mac：就是你这台 Mac；Windows：一台只属于你的虚拟 Mac |
| 会不会突然不能用 | 对方停机或被封就不能用 | 只取决于你自己的电脑 |

> 指纹服务只发设备指纹，**不会**经手你的 Apple ID、密码、令牌或照片。

---

## 1. 下载服务端

到 [Releases](https://github.com/CuteCatGYH/AClouDroid/releases) 下载对应文件：

| 系统 | 文件 |
|---|---|
| macOS 12 及以上（Apple 芯片和 Intel 都行） | `anisette-server-macos` |
| Windows 10 / 11（64 位，测试中） | `anisette-server-windows.exe` |

也可以自己编译，源码在仓库的 `scripts/anisette_server/`，每个平台目录里都有 `README.md`。

---

## 2A. 在 Mac 上运行

1. 这台 Mac 要登录过 Apple ID（系统设置里能看到你的账户即可）。
2. 打开「终端」，进入下载目录，执行：

   ```sh
   chmod +x anisette-server-macos
   xattr -d com.apple.quarantine anisette-server-macos 2>/dev/null
   ./anisette-server-macos --listen 0.0.0.0:6969
   ```

3. 第一次运行会生成一个随机密钥，并打印出类似这样的地址：

   ```
   Paste one of these into AClouDroid -> 设置 -> 大文件上传 -> 设备指纹地址:
     http://192.168.1.23:6969/AbC...xyz/
   self-test OK (device <Mac16,10>)
   ```

   看到 `self-test OK` 就说明能用了。

4. macOS 可能弹出「是否允许接受传入的网络连接」，选「允许」。

**防止睡眠**：Mac 睡着了，设备就拿不到指纹。可以在「系统设置 -> 电池/能耗」里关掉自动睡眠，或在终端里这样运行：

```sh
caffeinate -s ./anisette-server-macos --listen 0.0.0.0:6969
```

## 2B. 在 Windows 上运行

> 🧪 **测试中**：Windows 版的指纹已能通过苹果第一步校验，但完整登录还没测通。苹果可能拒绝部分账号（错误 `-22410`）。遇到这种情况，请改用 Mac 或默认的公开服务器。

1. 从 Microsoft Store 或 [Apple 官网](https://support.apple.com/zh-cn/103232) 安装 **iCloud for Windows**，打开一次并登录你的 Apple ID。
2. 把 `anisette-server-windows.exe` 放到一个文件夹，例如 `C:\AClouDroid\`。
3. 打开「命令提示符」或 PowerShell：

   ```bat
   cd C:\AClouDroid
   anisette-server-windows.exe --listen 0.0.0.0:6969
   ```

4. Windows 防火墙弹窗时，勾选「专用网络」，点「允许访问」。
5. 和 Mac 一样，记下打印出来的地址，确认有 `self-test OK`。

**开机自动运行**（可选）：打开「任务计划程序」-> 创建基本任务 -> 触发器选「登录时」-> 操作选「启动程序」，程序填 `C:\AClouDroid\anisette-server-windows.exe`，参数填 `--listen 0.0.0.0:6969`。

**防止睡眠**：「设置 -> 系统 -> 电源」里把「睡眠」设为「从不」（至少插电时）。

---

## 3. 让设备连上你的电脑

按你的情况选一种：

### 方式一：同一个 Wi-Fi（最简单）

设备和电脑连同一个路由器时，直接用打印出来的 `http://192.168.x.x:6969/<密钥>/` 地址即可。出门在外就连不上了。

### 方式二：Tailscale（推荐，出门也能用）

[Tailscale](https://tailscale.com/) 会把你的设备组成一个加密的私人网络，不用开放任何端口。

1. 电脑和 Android 设备都安装 Tailscale，用同一个账号登录。
2. 在 Tailscale 里查看电脑的地址（`100.x.y.z`）。
3. 设备上填 `http://100.x.y.z:6969/<密钥>/`。

### 方式三：内网穿透（frp、cloudflared 等）

把电脑的 `127.0.0.1:6969` 映射成一个公网地址。服务端可以只监听本机（不加 `--listen`，默认就是 `127.0.0.1:6969`），由穿透工具转发。

**穿透必须走 HTTPS。** 地址里的密钥一旦在公网上明文传输，就可能被中途看到。

| 穿透工具 | HTTPS 怎么来 |
|---|---|
| Cloudflare Tunnel | 自带，什么都不用配（推荐） |
| frp 的 `https` 类型代理、花生壳等 | 在穿透平台上配证书 |
| frp 的 `tcp` 类型、只给 IP 和端口的穿透 | 服务端自己开 HTTPS，见下面「开启 HTTPS」 |

以 Cloudflare Tunnel 为例：

```sh
cloudflared tunnel --url http://127.0.0.1:6969
```

它会给出一个 `https://xxxx.trycloudflare.com` 地址，设备上填 `https://xxxx.trycloudflare.com/<密钥>/`。密钥可以用 `--show-url` 查看。

> 临时的 trycloudflare 地址每次启动都会变。长期使用请在 Cloudflare 后台建一条固定的命名隧道。

### 方式四：公网 IP + 端口转发

如果你的宽带有公网 IP，可以在路由器里把外网端口转发到电脑的 6969 端口。**这种方式必须开启 HTTPS**，见下一节。

---

## 3.5 开启 HTTPS（公网 IP 或 TCP 穿透时必做）

App 会严格检查证书，所以**自签名证书用不了**。你需要一个域名，加一张正规证书。免费的 Let's Encrypt 就行。

**第 1 步：准备域名。** 用自己的域名，或者免费的动态域名（DDNS，例如 DuckDNS），把它解析到你的公网 IP。

**第 2 步：申请证书。**

- Mac：用 [acme.sh](https://github.com/acmesh-official/acme.sh) 或 certbot。拿到的 `fullchain.pem` 和 `privkey.pem` 就是要用的两个文件。
- Windows：用 [win-acme](https://www.win-acme.com/)，选择导出 **PFX** 文件，记下你设的 PFX 密码。

很多家庭宽带封了 80 端口，这时请用 **DNS 验证**（acme.sh 的 `--dns`、win-acme 的 DNS 选项）。

**第 3 步：带上证书启动。**

Mac：

```sh
./anisette-server-macos --listen 0.0.0.0:6969 --tls-cert fullchain.pem --tls-key privkey.pem
```

Windows：

```bat
anisette-server-windows.exe --listen 0.0.0.0:6969 --tls-pfx cert.pfx --tls-pfx-password 你的PFX密码
```

启动后横幅会显示 `Listening on ... (HTTPS)`，地址变成 `https://你的域名:6969/<密钥>/`。把它填进 App。

**第 4 步：记得续期。** Let's Encrypt 证书 90 天过期。acme.sh 和 win-acme 都会自动续期，续期后重启一次服务端即可。证书过期时，服务端启动会打印警告。

---

## 4. 在 App 里填写

1. 打开 AClouDroid -> 设置 -> 自动备份 -> **大文件上传**。
2. 已经登录过的话，先点「退出登录」。**换指纹来源后必须重新登录**，因为苹果把登录和设备绑在一起。
3. 在「设备指纹地址」里粘贴你的地址（结尾的 `/` 保留），点「保存地址」。
4. 输入 Apple ID 和密码，点「登录」，有验证码就输入。
5. 登录后可以点「检查连接」确认。

换回公开服务器：退出登录，点「恢复默认地址」，勾选同意说明，再登录一次。

---

## 5. 安全说明

- 地址里的那串密钥就是访问凭证。它是 43 位随机字符，猜中的可能几乎为零。**不要发给别人**，也不要截图公开。
- 同一个 IP 在 10 分钟内猜错 10 次，会被封 30 分钟。
- 公网访问一定要用 HTTPS（见 3.5 节），否则密钥会被明文传输。
- 密钥泄露了：运行一次 `--new-token` 生成新密钥，再把新地址填回 App 并重新登录。
- 配置文件位置（里面存着密钥）：
  - macOS：`~/Library/Application Support/AClouDroidAnisette/config.json`
  - Windows：`%APPDATA%\AClouDroidAnisette\config.json`
- 服务端每分钟最多回应 30 次指纹请求，多了会拒绝。
- 不用了就关掉程序，并在 Apple ID 设备列表里移除对应设备。

## 6. 常见问题

| 现象 | 原因和办法 |
|---|---|
| App 提示连不上指纹服务器 | 电脑没开、睡着了、程序没运行，或设备和电脑不在同一网络。先在设备的浏览器打开 `<你的地址>health`，能看到 `{"ok":true...}` 才算通。 |
| 打开地址显示 404 | 密钥不对。用 `--show-url` 重新打印正确地址。如果之前猜错太多次，这个 IP 会被封 30 分钟，等一会儿再试。 |
| HTTPS 地址打不开，提示证书错误 | 证书过期，或填的域名和证书上的不一致。不能用 IP 加 HTTPS，要用证书上的域名。 |
| Windows 上 `self-test` 失败 | 没装 iCloud for Windows，或装了但从没打开登录过。装好后打开一次 iCloud。 |
| 登录成功，但上传报「登录已过期」 | 指纹来源换过了。退出登录，再用现在的地址重新登录。 |
| 上传中途电脑睡着了 | 字节开始传之后不再需要指纹，正在传的文件不受影响；下一个文件会失败并稍后重试。 |

---

**免责声明**：本功能不是 Apple 官方提供的，按现状提供，不作任何保证。使用风险由你自己承担。开发者不对账号受限、数据丢失或其他后果负责。
