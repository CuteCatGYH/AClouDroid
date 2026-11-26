package native

// Anisette = Apple's machine-provisioning headers (device fingerprint + one-time code).
//
// Android cannot produce them itself yet, so they come from an HTTP provider that returns one
// JSON object of headers per GET. The format is the common "anisette v1" shape, so the provider
// can be our Mac helper (scripts/anisette_mac, reached over `adb reverse`) today and a different
// provider later without changing this side.

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Anisette map[string]string

var mdKeys = []string{"X-Apple-I-MD-LU", "X-Apple-I-MD-RINFO", "X-Apple-I-MD-M", "X-Apple-I-MD", "X-Mme-Device-Id"}

// FetchAnisette asks the provider for a fresh header set. The one-time code inside is
// short-lived, so callers fetch once per login or per upload, never cache across them.
func FetchAnisette(providerURL string) (Anisette, error) {
	if providerURL == "" {
		return nil, fmt.Errorf("no anisette provider configured")
	}
	c := &http.Client{Timeout: 15 * time.Second}
	resp, err := c.Get(providerURL)
	if err != nil {
		return nil, fmt.Errorf("anisette provider unreachable: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("anisette provider HTTP %d", resp.StatusCode)
	}
	var generic map[string]interface{}
	if err := json.Unmarshal(raw, &generic); err != nil {
		return nil, fmt.Errorf("anisette provider reply is not JSON")
	}
	a := Anisette{}
	for k, v := range generic {
		if s, ok := v.(string); ok {
			a[canonicalAnisetteKey(k)] = s
		}
	}
	for _, k := range []string{"X-Apple-I-MD", "X-Apple-I-MD-M", "X-Mme-Device-Id"} {
		if a[k] == "" {
			return nil, fmt.Errorf("anisette provider reply misses %s", k)
		}
	}
	now := time.Now()
	if a["X-Apple-I-Client-Time"] == "" {
		a["X-Apple-I-Client-Time"] = now.UTC().Format("2006-01-02T15:04:05Z")
	}
	if a["X-Apple-I-TimeZone"] == "" {
		tz, _ := now.Zone()
		a["X-Apple-I-TimeZone"] = tz
	}
	if a["X-Apple-I-MD-RINFO"] == "" {
		// Routing info; 17106176 is the value every known client sends.
		a["X-Apple-I-MD-RINFO"] = "17106176"
	}
	return a, nil
}

// canonicalAnisetteKey folds provider spellings (X-MMe-Client-Info, x-apple-i-md, ...) onto the
// spelling this package reads.
func canonicalAnisetteKey(k string) string {
	switch strings.ToLower(k) {
	case "x-mme-client-info":
		return "X-Mme-Client-Info"
	case "x-mme-device-id":
		return "X-Mme-Device-Id"
	case "x-apple-i-srl-no", "x-apple-srl-no":
		return "X-Apple-SRL-NO"
	case "x-apple-i-md":
		return "X-Apple-I-MD"
	case "x-apple-i-md-m":
		return "X-Apple-I-MD-M"
	case "x-apple-i-md-lu":
		return "X-Apple-I-MD-LU"
	case "x-apple-i-md-rinfo":
		return "X-Apple-I-MD-RINFO"
	case "x-apple-i-client-time":
		return "X-Apple-I-Client-Time"
	case "x-apple-i-timezone":
		return "X-Apple-I-TimeZone"
	case "x-apple-locale":
		return "X-Apple-Locale"
	}
	return k
}

func (a Anisette) pick(keys ...string) map[string]string {
	out := map[string]string{}
	for _, k := range keys {
		if v := a[k]; v != "" {
			out[k] = v
		}
	}
	return out
}

// clientInfoFor builds the X-Mme-Client-Info for one Apple service. Mac-style providers get the
// service's own Mac suffix ("<Model> <macOS;...> <suffix>"). iCloud for Windows identities
// ("<PC> <Windows; ...> <com.apple.AOSKit/...>") are sent verbatim: Apple ties the Windows
// machine to that exact string, and rewriting the suffix gets HTTP 403 (tested 2026-11-25).
func clientInfoFor(ani Anisette, macSuffix string) string {
	full := strings.TrimSpace(ani["X-Mme-Client-Info"])
	if isWindowsClientInfo(full) {
		return full
	}
	return deviceInfoPrefix(full) + " " + macSuffix
}

func isWindowsClientInfo(full string) bool {
	return strings.HasPrefix(full, "<PC>") || strings.Contains(full, "<Windows")
}

// deviceInfoPrefix keeps "<Model> <OS;ver;build>" from the device description.
func deviceInfoPrefix(full string) string {
	n := 0
	for i, r := range full {
		if r == '>' {
			n++
			if n == 2 {
				return full[:i+1]
			}
		}
	}
	return "<Mac16,10> <macOS;26.7.3;25G342>"
}

// deviceVersions splits "<Mac16,10> <macOS;26.7.3;25G342>" into hardware model and OS version.
func deviceVersions(full string) (hw, sw string) {
	p := deviceInfoPrefix(full)
	parts := strings.SplitN(p, "> <", 2)
	if len(parts) != 2 {
		return "Mac16,10", "26.7.3"
	}
	hw = strings.TrimSpace(strings.TrimPrefix(parts[0], "<"))
	os := strings.Split(strings.TrimSuffix(parts[1], ">"), ";")
	if len(os) >= 2 {
		sw = strings.TrimSpace(os[1])
	}
	return hw, sw
}
