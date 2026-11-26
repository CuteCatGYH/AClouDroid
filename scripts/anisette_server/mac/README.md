# AClouDroid Anisette Server (macOS)

A lightweight, dependency-free HTTP server for macOS 12+ (universal `arm64` + `amd64`) that computes fresh Apple anisette device-fingerprint headers using macOS's native `AOSKit` and `AuthKit` private frameworks via Go + cgo (Objective-C runtime).

## Requirements

- macOS 12 or later
- Xcode Command Line Tools (`xcode-select --install`)
- Go 1.22+

## Build

```bash
chmod +x build.sh
./build.sh
```

This builds `arm64` and `amd64` slices and combines them with `lipo` into `./anisette-server`.

Verify architectures:

```bash
lipo -info ./anisette-server
```

## Usage

Run on loopback only (default `127.0.0.1:6969`):

```bash
./anisette-server
```

Expose to LAN / Tailscale / tunnel:

```bash
./anisette-server --listen 0.0.0.0:6969
```

Other flags:

- `--listen host:port` — Listen address (default `127.0.0.1:6969`)
- `--token value` — Override authentication token for this run
- `--new-token` — Regenerate and save a new token to config
- `--show-url` — Print connection URLs and exit
- `--version` — Print version and exit
- `--help` — Show usage

### Configuration

By default, the server stores its generated token in:

```text
~/Library/Application Support/AClouDroidAnisette/config.json
```

Directory permissions are `0700` and file permissions are `0600`. Set `ACLOUDDROID_ANISETTE_HOME` to override the config directory (useful for isolated testing).

## Smoke Test

```bash
TEST_HOME="$(mktemp -d)"
ACLOUDDROID_ANISETTE_HOME="$TEST_HOME" ./anisette-server --listen 127.0.0.1:16969 &
PID=$!
sleep 2

TOKEN="$(python3 -c "import json; print(json.load(open('$TEST_HOME/config.json'))['token'])")"

# 1. Health check
curl -sS "http://127.0.0.1:16969/$TOKEN/health"

# 2. Wrong token -> 404
curl -sS -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:16969/wrong-token/"

# 3. Two anisette calls across a 30s TOTP boundary return required keys and distinct X-Apple-I-MD
# (macOS AOSUtilities rotates X-Apple-I-MD every 30 seconds on :00 / :30 UTC boundaries)
R1="$(curl -sS "http://127.0.0.1:16969/$TOKEN/")"
WAIT="$((31 - ($(date +%s) % 30)))"
sleep "$WAIT"
R2="$(curl -sS "http://127.0.0.1:16969/$TOKEN/")"
python3 - "$R1" "$R2" <<'PY'
import json, sys
a, b = json.loads(sys.argv[1]), json.loads(sys.argv[2])
for k in ("X-Apple-I-MD", "X-Apple-I-MD-M", "X-Mme-Device-Id"):
    assert a.get(k) and b.get(k), f"missing {k}"
assert a["X-Apple-I-MD"] != b["X-Apple-I-MD"], "X-Apple-I-MD did not change"
print("OK: keys present and X-Apple-I-MD refreshed")
PY

kill "$PID"
wait "$PID" 2>/dev/null || true
rm -rf "$TEST_HOME"
```
