package icloudcore

import (
	"strings"
	"testing"
)

func TestNewSessionCookieParsing(t *testing.T) {
	cookieStr := `X-APPLE-WEBAUTH-TOKEN="v=2:t=AQAAAAB"; X-APPLE-WEBAUTH-HSA-TRUST="AQAAAAAAA="; X-APPLE-WEBAUTH-PCS-CloudKit="PCS_TOKEN_123=="; $Path=/; $Version=1`

	session, err := NewSession(cookieStr, true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}

	if session.CookieMap["X-APPLE-WEBAUTH-TOKEN"] != "v=2:t=AQAAAAB" {
		t.Errorf("expected trimmed token, got %s", session.CookieMap["X-APPLE-WEBAUTH-TOKEN"])
	}
	if session.CookieMap["X-APPLE-WEBAUTH-HSA-TRUST"] != "AQAAAAAAA=" {
		t.Errorf("expected trimmed hsa trust, got %s", session.CookieMap["X-APPLE-WEBAUTH-HSA-TRUST"])
	}
	if session.CookieMap["X-APPLE-WEBAUTH-PCS-CloudKit"] != "PCS_TOKEN_123==" {
		t.Errorf("expected trimmed pcs, got %s", session.CookieMap["X-APPLE-WEBAUTH-PCS-CloudKit"])
	}
	if _, exists := session.CookieMap["$Path"]; exists {
		t.Errorf("expected $Path to be skipped")
	}

	header := session.BuildCookieHeader()
	if !strings.Contains(header, "X-APPLE-WEBAUTH-TOKEN=v=2:t=AQAAAAB") {
		t.Errorf("header missing trimmed token: %s", header)
	}

	req, err := session.NewRequest("POST", "https://setup.icloud.com.cn/setup/ws/1/validate", "{}")
	if err != nil {
		t.Fatalf("NewRequest failed: %v", err)
	}
	reqCookie := req.Header.Get("Cookie")
	if !strings.Contains(reqCookie, "X-APPLE-WEBAUTH-TOKEN=v=2:t=AQAAAAB") {
		t.Errorf("expected request cookie to contain token, got: %s", reqCookie)
	}
	if req.Header.Get("Origin") != "https://www.icloud.com.cn" {
		t.Errorf("expected CN origin, got %s", req.Header.Get("Origin"))
	}
}

func TestCoreInitSessionFailureOutput(t *testing.T) {
	// An invalid cookie string or invalid URL should still return isChina=true
	resJSON := InitSession("invalid;cookie", true)
	if !strings.Contains(resJSON, `"isChina":true`) {
		t.Errorf("expected result to contain isChina:true, got: %s", resJSON)
	}
}

// TestBuildCookieHeaderIsDeterministic guards P2-019.
//
// Go randomises map iteration order, so the pre-fix implementation emitted the same cookie set
// in a different order on every call. Kotlin compares the persisted header with the freshly
// serialised one to decide whether credentials rotated, so that jitter made every cold start
// look like a rotation ("Cookies rotated (len 2893 -> 2893)") and bumped the session generation,
// marking the entire cached photo library stale.
func TestBuildCookieHeaderIsDeterministic(t *testing.T) {
	// Enough keys that an unsorted map would almost certainly produce a different order
	// across runs (Go's per-iteration randomisation makes this reliable in practice).
	cookieStr := strings.Join([]string{
		`X-APPLE-WEBAUTH-TOKEN="tok"`,
		`X-APPLE-WEBAUTH-HSA-TRUST="trust"`,
		`X-APPLE-WEBAUTH-PCS-CloudKit="pcs"`,
		`X-APPLE-WEBAUTH-PCS-Photos="photos"`,
		`X-APPLE-WEBAUTH-PCS-Drive="drive"`,
		`X-APPLE-WEBAUTH-USER="user"`,
		`X-APPLE-DS-WEB-SESSION-TOKEN="dsweb"`,
		`X-APPLE-WEBAUTH-LOGIN="login"`,
	}, "; ")

	session, err := NewSession(cookieStr, true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}

	first := session.BuildCookieHeader()
	if first == "" {
		t.Fatal("BuildCookieHeader returned empty string")
	}

	for i := 0; i < 100; i++ {
		if got := session.BuildCookieHeader(); got != first {
			t.Fatalf("BuildCookieHeader is not deterministic (iteration %d):\n first=%s\n got  =%s", i, first, got)
		}
	}

	// Keys must come out sorted, which is what makes the output canonical.
	sorted := strings.Split(first, "; ")
	for i := 1; i < len(sorted); i++ {
		prevKey := strings.SplitN(sorted[i-1], "=", 2)[0]
		curKey := strings.SplitN(sorted[i], "=", 2)[0]
		if prevKey > curKey {
			t.Errorf("keys not sorted: %q came before %q", prevKey, curKey)
		}
	}
}

// TestBuildCookieHeaderPreservesValueBytes ensures sorting did not break values that contain
// '=' (base64 tokens) or the cookie-name separator.
func TestBuildCookieHeaderPreservesValueBytes(t *testing.T) {
	cookieStr := `X-APPLE-WEBAUTH-TOKEN="v=2:t=AQAAAAB/cd=="; X-APPLE-WEBAUTH-HSA-TRUST="AQAAAAAAA="`
	session, err := NewSession(cookieStr, true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}

	header := session.BuildCookieHeader()
	if !strings.Contains(header, "X-APPLE-WEBAUTH-TOKEN=v=2:t=AQAAAAB/cd==") {
		t.Errorf("base64 value was mangled: %s", header)
	}
	if !strings.Contains(header, "X-APPLE-WEBAUTH-HSA-TRUST=AQAAAAAAA=") {
		t.Errorf("hsa trust value was mangled: %s", header)
	}
}

func TestSessionSummaryAndCookies(t *testing.T) {
	cookieStr := `X-APPLE-WEBAUTH-TOKEN="valid-token"; X-APPLE-WEBAUTH-HSA-TRUST="valid-trust"`

	// GetSessionStateSummary / GetCurrentCookieString read the process-global session, not the
	// struct returned by NewSession (ISS-063 keeps installation separate from construction).
	session, err := NewSession(cookieStr, true)
	if err != nil {
		t.Fatalf("failed to create session: %v", err)
	}
	installSessionForTest(t, session)

	summary := GetSessionStateSummary()
	if !strings.Contains(summary, `"hasAuthToken":true`) {
		t.Errorf("summary missing hasAuthToken: %s", summary)
	}
	if !strings.Contains(summary, `"hasHsaTrust":true`) {
		t.Errorf("summary missing hasHsaTrust: %s", summary)
	}

	currentCookies := GetCurrentCookieString()
	if !strings.Contains(currentCookies, "X-APPLE-WEBAUTH-TOKEN=valid-token") {
		t.Errorf("GetCurrentCookieString missing token: %s", currentCookies)
	}
}
