# AClouDroid Anisette Server — shared spec

A tiny HTTP server a user runs on their OWN Mac or Windows PC. The AClouDroid Android App fetches
fresh Apple "anisette" headers (device fingerprint + one-time code) from it before each native
login/upload. Apple rejects fingerprints older than ~2 minutes, so every request must compute a
NEW set. The App reaches the server over LAN, a tunnel (Tailscale, frp, cloudflared) or a public IP.

Two implementations share this exact HTTP contract:

- `mac/`     — Go + cgo (Objective-C runtime), macOS 12+, arm64 and amd64.
- `windows/` — C++17, MSVC (Visual Studio), x64, no third-party dependencies.

## HTTP contract

| Request | Reply |
|---|---|
| `GET /<token>/` (also `/<token>`) | 200, `application/json`, one flat JSON object of string values (below) |
| `GET /<token>/health` | 200, `{"ok":true,"platform":"macos"\|"windows","version":"1.0.0"}` without computing anisette |
| wrong/missing token, any other path | 404, empty body (do not reveal whether the token was close) |
| method other than GET/HEAD | 405 |
| more than 30 anisette requests in the last 60 s | 429 |
| Apple library failure | 500, `{"error":"<short reason>"}` |

JSON keys of the anisette reply (exact spelling; all values are strings):

| Key | Value |
|---|---|
| `X-Apple-I-MD` | one-time password (base64), fresh per request |
| `X-Apple-I-MD-M` | machine id (base64) |
| `X-Apple-I-MD-LU` | local user id |
| `X-Apple-I-MD-RINFO` | `"17106176"` |
| `X-Mme-Device-Id` | device unique id (UUID, uppercase) |
| `X-Mme-Client-Info` | device description, e.g. `<MacBookPro15,1> <Mac OS X;10.15.2;19C57> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>` |
| `X-Apple-I-Client-Time` | now, UTC, `2006-01-02T15:04:05Z` |
| `X-Apple-I-TimeZone` | local zone abbreviation, e.g. `CST` (or `UTC`) |
| `X-Apple-Locale` | e.g. `zh_CN` / `en_US` |
| `X-Apple-I-SRL-NO` | serial number (optional; omit if unknown) |

`X-Apple-I-MD`, `X-Apple-I-MD-M` and `X-Mme-Device-Id` are REQUIRED; if any is empty, reply 500.
The Device-Id, LU and Client-Info must stay STABLE across requests and restarts (Apple binds the
login to them). Persist any generated id in the config file.

## Security (mandatory)

1. **Token.** On first start generate 32 random bytes from the OS CSPRNG, encode base64url without
   padding (43 chars), store in the config file. `--token <value>` overrides; `--new-token`
   regenerates. Compare in constant time. Never print MD/OTP values or the token in request logs
   (print the token only once, in the startup banner, inside the URL to paste into the App).
2. **Config file**, readable by the current user only:
   - macOS: `~/Library/Application Support/AClouDroidAnisette/config.json` (dir 0700, file 0600)
   - Windows: `%APPDATA%\AClouDroidAnisette\config.json`
   Content: `{"token":"...","deviceId":"..."}` (+ any other stable ids you need).
3. **Listen address.** Default `127.0.0.1:6969`. `--listen 0.0.0.0:6969` (or any host:port) to
   accept LAN/tunnel/public traffic; print a warning line when not loopback.
4. One anisette computation at a time (mutex). Request line + headers capped at 8 KiB, read
   timeout 10 s, ignore any request body.
5. Log one line per request: time, remote IP, path class (`anisette`/`health`/`denied`/`blocked`), status.
6. **Brute-force guard.** Per remote IP: 10 wrong-token/unknown-path requests within 10 min →
   that IP gets 404 for EVERYTHING (even the right token) for 30 min; log one `blocked` line.
   Never block loopback (tunnels make every visitor look like 127.0.0.1; blocking it would let a
   stranger lock the owner out). Cap tracked IPs at 4096.
7. **HTTPS.** Plain HTTP stays the default (home Wi-Fi / Tailscale). For a public IP or a TCP
   tunnel the server can terminate TLS itself (TLS 1.2+):
   - macOS: `--tls-cert fullchain.pem --tls-key privkey.pem` (PEM, e.g. from Let's Encrypt).
   - Windows: `--tls-pfx cert.pfx [--tls-pfx-password <pw>]` (Schannel; win-acme exports PFX).
   With TLS the banner prints `https://<DNS name from the certificate>:<port>/<token>/` (raw IPs
   would fail host-name checks) and warns if the certificate has expired. A plain-HTTP request on
   the TLS port must not crash the server. Without TLS on a non-loopback address, print:
   `WARNING: plain HTTP on a non-loopback address (...). Fine on home Wi-Fi or Tailscale; for a public IP use TLS or an HTTPS tunnel.`
   When listening on loopback only, do not list LAN IPs in the banner; print
   `(loopback only: use --listen 0.0.0.0:<port> for LAN, or put a tunnel in front)` instead.

## Startup banner (stdout)

```
AClouDroid Anisette Server 1.0.0 (windows)
Listening on 0.0.0.0:6969
Paste one of these into AClouDroid -> 设置 -> 大文件上传 -> 设备指纹地址:
  http://192.168.31.59:6969/<token>/
  http://127.0.0.1:6969/<token>/
```
List every non-loopback IPv4 of the machine. Then run a self-test (compute anisette once) and print
`self-test OK (device <first part of Client-Info>)` or the failure reason, and keep serving.

## CLI flags (both)

`--listen host:port`, `--token value`, `--new-token`, `--show-url` (print banner URLs and exit),
`--version`, `--help`, plus the TLS flags above. Env `ACLOUDDROID_ANISETTE_HOME` overrides the
config directory (for tests).

## Platform notes

### macOS
Use the same private calls as the existing `scripts/anisette_mac/anisette.swift`:
`dlopen` AOSKit + AuthKit; `[AOSUtilities retrieveOTPHeadersForDSID:@"-2"]` -> keys `X-Apple-MD`
(→ `X-Apple-I-MD`) and `X-Apple-MD-M` (→ `X-Apple-I-MD-M`); `[AOSUtilities machineSerialNumber]`;
`[AKDevice currentDevice]` -> `localUserUUID` (LU), `serverFriendlyDescription` (Client-Info),
`uniqueDeviceIdentifier` (Device-Id), `locale`. Wrap each call in an autorelease pool. These ids are
the Mac's own, so nothing needs persisting except the token.

### Windows (iCloud for Windows) — EXPERIMENTAL

Status 2026-11-26: GrandSlam `init` accepts the fingerprint ONLY with iCloud's own identity:
`X-Mme-Device-Id` = AOSKit `_GetDeviceId()`, `X-Apple-I-MD-LU` = `_GetLocalUserUUID()`,
`X-Mme-Client-Info` = AOSKit's own `<PC> <Windows; ...> <com.apple.AOSKit/... (com.apple.iCloud/...)>`
(sent verbatim by the App, never rewritten). A random Device-Id or a fake Mac Client-Info gets
-22410. A full login (2FA + MobileMe) has not succeeded yet: after one 2FA attempt, the tester's
account + this PC kept returning -22410 for 15+ hours, while unknown accounts and public servers
behaved normally. The notes below describe the first version and are superseded where they differ.

Follow AltServer-Windows `AnisetteDataManager.cpp` (non-SPOOF_MAC path):
- Find iCloud's DLL folder: first the Microsoft Store package
  (`Get-AppxPackage AppleInc.iCloud` -> `InstallLocation\iCloud\`; from C++ use
  `PackageManager`/`GetPackagePathByFullName` or scan `C:\Program Files\WindowsApps\AppleInc.iCloud_*_x64__*\iCloud`),
  then the classic installer paths
  `%CommonProgramFiles%\Apple\Apple Application Support\` (objc.dll, Foundation.dll) and
  `%CommonProgramFiles%\Apple\Internet Services\` (AOSKit.dll). `--icloud-dir <path>` overrides.
- `SetDllDirectoryW(<dir>)`, `SetCurrentDirectoryW(<dir>)`, then `LoadLibraryW` objc.dll,
  CoreFoundation.dll (if present), Foundation.dll, AOSKit.dll.
- `objc_getClass`, `sel_registerName`, `objc_msgSend` from objc.dll. Cast `objc_msgSend` to the
  exact prototype per call. OTP: `[AOSUtilities retrieveOTPHeadersForDSID:@"-2"]`, then
  `objectForKey:@"X-Apple-MD-M"` / `@"X-Apple-MD"`, `description` + `UTF8String`.
- Device-Id: a random UUID generated once (uppercase), persisted in config.
- LU: base64 of the Device-Id string bytes (as AltServer does).
- Client-Info: `<MacBookPro15,1> <Mac OS X;10.15.2;19C57> <com.apple.AuthKit/1 (com.apple.dt.Xcode/3594.4.19)>`.
- Serial: `C02LKHBBFD57`.
- Wrap each computation in `NSAutoreleasePool` (`[[NSAutoreleasePool alloc] init]` … `drain`) on
  one dedicated worker thread.
- If the DLLs cannot load, explain in the banner: install iCloud for Windows and sign in once.

## Deliverables per platform

- Source + build script (`build.sh` for mac producing universal `anisette-server` via `lipo`;
  `build.bat` for windows using `vswhere` to find MSVC, producing `anisette-server.exe`).
- `README.md` with build steps.
- A smoke test: start the server on loopback, call `/health` and `/<token>/`, check the 3 required
  keys are non-empty and that two consecutive calls return different `X-Apple-I-MD`; wrong token -> 404.
