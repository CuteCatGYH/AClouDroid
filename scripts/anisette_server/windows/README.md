# AClouDroid Anisette Server (Windows)

Native C++17 (MSVC x64) HTTP/HTTPS server using Winsock2, Windows Schannel (TLS 1.2+), and iCloud for Windows DLLs (`objc.dll`, `CoreFoundation.dll`, `Foundation.dll`, `AOSKit.dll`) with zero third-party dependencies.

## Prerequisites

- Windows 10/11 (x64)
- **iCloud for Windows** installed (Microsoft Store edition or classic Apple installer) and signed in at least once
- **Visual Studio** (2019/2022+) or **Build Tools for Visual Studio** with the **Desktop development with C++** (MSVC x64) workload (for building from source)

## Build

Run from Command Prompt or PowerShell inside `windows\`:

```cmd
build.bat
```

`build.bat` locates Visual Studio via `vswhere.exe`, initializes the x64 MSVC toolchain, links only Windows system libraries (`ws2_32`, `iphlpapi`, `bcrypt`, `advapi32`, `shell32`, `ole32`, `secur32`, `crypt32`, `version`), and outputs `anisette-server.exe`.

## Usage

```cmd
anisette-server.exe
```

By default the server binds to `127.0.0.1:6969` (loopback only). To accept connections from your LAN or a tunnel (Tailscale, frp, cloudflared):

```cmd
anisette-server.exe --listen 0.0.0.0:6969
```

### CLI Flags

| Flag | Description |
|---|---|
| `--listen host:port` | Address to listen on (default: `127.0.0.1:6969`) |
| `--token value` | Override the auth token for this run |
| `--new-token` | Regenerate and persist a new random token |
| `--show-url` | Print banner URLs and exit |
| `--icloud-dir path` | Override iCloud DLL directory |
| `--tls-pfx file` | Enable HTTPS (TLS 1.2+) via Schannel using a PKCS#12 (`.pfx`) certificate archive |
| `--tls-pfx-password pw` | Password for the `--tls-pfx` archive (omit if empty) |
| `--version` | Print version and exit |
| `--help` | Print usage help and exit |

### Environment Variables

| Variable | Description |
|---|---|
| `ACLOUDDROID_ANISETTE_HOME` | Override the configuration directory (useful for isolated tests) |

### Configuration

The persistent auth token is stored in:

```
%APPDATA%\AClouDroidAnisette\config.json
```

The directory and file are created with a protected DACL restricting access to the current Windows user. Device identity (`X-Mme-Device-Id`, `X-Apple-I-MD-LU`, `X-Mme-Client-Info`) is read directly from the installed iCloud for Windows (`AOSKit.dll`) rather than generated randomly; any legacy `deviceId` field in older `config.json` files is ignored.

## TLS / HTTPS Usage

Plain HTTP is the default for home Wi-Fi or encrypted tunnels such as Tailscale. If you expose the server on a public IP or a raw TCP tunnel, enable native TLS 1.2+ with a PKCS#12 (`.pfx`) certificate (for example, exported by [win-acme](https://www.win-acme.com/) from Let's Encrypt):

```cmd
anisette-server.exe --listen 0.0.0.0:6969 --tls-pfx C:\certs\anisette.pfx --tls-pfx-password secret
```

- The startup banner prints `https://<DNS name from cert>:<port>/<token>/` for each DNS name in the certificate SAN (or Subject CN) and warns if the certificate has expired.
- Plain-HTTP requests sent to the TLS port are rejected cleanly without crashing or hanging the server.

## HTTP Endpoints & Security

- `GET /<token>/health` — returns `{"ok":true,"platform":"windows","version":"1.0.0"}` without computing anisette
- `GET /<token>/` (or `/<token>`) — computes and returns fresh anisette headers as a flat JSON object (`X-Apple-I-MD`, `X-Apple-I-MD-M`, `X-Apple-I-MD-LU`, `X-Apple-I-MD-RINFO`, `X-Mme-Device-Id`, `X-Mme-Client-Info`, `X-Apple-I-Client-Time`, `X-Apple-I-TimeZone`, `X-Apple-Locale`; `X-Apple-I-SRL-NO` is omitted). Uses iCloud's own device UDID, local user UUID, and Windows client-info string (populated with the installed iCloud version); returns `500` if any of these cannot be resolved from `AOSKit.dll`
- Wrong/missing token or unknown path — `404` with empty body (token comparison is constant-time)
- Method other than `GET`/`HEAD` — `405`
- Rate limit — max 30 anisette requests per 60 seconds (`429`)
- Brute-force guard — 10 wrong-token / unknown-path requests from a non-loopback IP within 10 minutes blocks that IP (`404` for all requests, logged as `blocked`) for 30 minutes (up to 4,096 tracked IPs; loopback is never blocked)
- Request headers capped at 8 KiB with a 10 s read timeout

## Smoke Test

```powershell
# Use a temporary config folder and non-default port
$env:ACLOUDDROID_ANISETTE_HOME = "$env:TEMP\aclouddroid-test"
.\anisette-server.exe --listen 127.0.0.1:16969

# Health check
curl.exe -m 10 -i http://127.0.0.1:16969/<token>/health

# Anisette fetch (run twice; X-Apple-I-MD changes each request)
curl.exe -m 10 -s http://127.0.0.1:16969/<token>/

# Wrong token -> 404
curl.exe -m 10 -i http://127.0.0.1:16969/wrongtoken/
```
