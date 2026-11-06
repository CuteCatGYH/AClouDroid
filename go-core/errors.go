package icloudcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strings"
)

// Structured error codes (P2-015).
//
// Every exported function that returns JSON to the Kotlin layer embeds [ResultBase], which
// carries a machine-readable ErrorCode and the raw HttpStatus. Before this existed, the Android
// side had to guess the failure kind by regex-matching `errorMessage` — which misfired twice in
// production (ISS-013 and ISS-015) because CloudKit's base64 `continuationMarker` /
// `syncToken` / record UUIDs routinely contain the literal digits "401" and "421".
//
// New codes must be added here, never invented ad-hoc at a call site, so Kotlin can rely on a
// closed set.
const (
	// ErrCodeAuthExpired means the request needs a fresh session: no active session, missing
	// CloudKit database URL, or HTTP 401/421 from Apple.
	ErrCodeAuthExpired = "AUTH_EXPIRED"
	// ErrCodeNotFound is a genuine 404 (record or endpoint missing).
	ErrCodeNotFound = "NOT_FOUND"
	// ErrCodeRateLimited is an HTTP 429 / CloudKit throttle. Retrying with backoff is correct;
	// rebuilding the session is not.
	ErrCodeRateLimited = "RATE_LIMITED"
	// ErrCodeTimeout covers transport-level timeouts (context deadline exceeded, Client.Timeout).
	ErrCodeTimeout = "TIMEOUT"
	// ErrCodeNetwork covers DNS / TLS / connection failures that are not timeouts.
	ErrCodeNetwork = "NETWORK"
	// ErrCodeParse is a JSON unmarshal failure on a response we did receive.
	ErrCodeParse = "PARSE"
	// ErrCodeZoneMissing is reported by CheckPrimaryZone when PrimarySync is absent.
	ErrCodeZoneMissing = "ZONE_MISSING"
	// ErrCodeUnknown is the catch-all.
	ErrCodeUnknown = "UNKNOWN"
)

// ResultBase is embedded in every exported result struct.
//
// Go promotes anonymous struct fields at the top level of the JSON object, so the wire format
// stays flat:
//
//	{"success":false,"errorCode":"AUTH_EXPIRED","httpStatus":401,"errorMessage":"..."}
//
// That keeps the change backward compatible: Kotlin's existing `optBoolean("success")` and
// `optString("errorMessage")` reads keep working, and the new fields are additive.
type ResultBase struct {
	Success      bool   `json:"success"`
	ErrorCode    string `json:"errorCode,omitempty"`
	HttpStatus   int    `json:"httpStatus,omitempty"`
	ErrorMessage string `json:"errorMessage,omitempty"`
}

// okBase builds a success base.
func okBase() ResultBase {
	return ResultBase{Success: true}
}

// failBase builds a failure base.
func failBase(code string, status int, msg string) ResultBase {
	return ResultBase{
		Success:      false,
		ErrorCode:    code,
		HttpStatus:   status,
		ErrorMessage: msg,
	}
}

// ClassifyHTTPStatus maps an HTTP status code to a stable error code.
//
// Anything below 200 or unclassified is reported as UNKNOWN with the real status preserved, so
// the Kotlin side can still surface the number to the user.
func ClassifyHTTPStatus(status int) string {
	switch {
	case status == http.StatusUnauthorized || status == http.StatusMisdirectedRequest:
		// 401 / 421: the two codes Apple uses for an expired or misrouted session.
		return ErrCodeAuthExpired
	case status == http.StatusNotFound:
		return ErrCodeNotFound
	case status == http.StatusTooManyRequests:
		return ErrCodeRateLimited
	case status == http.StatusRequestTimeout:
		// 408 Request Timeout.
		//
		// This used to fall through to UNKNOWN, and Drive's upload path then treated it as a
		// non-retryable failure — so a transfer that the edge simply cut short was reported as a
		// generic error, the cached file was left in place, and the next user tap re-hashed the
		// whole file and started from byte 0. It is a transport timeout in every way that matters
		// to the caller (AGENTS §7.1): back off and retry, never rebuild the session.
		return ErrCodeTimeout
	default:
		return ErrCodeUnknown
	}
}

// ClassifyNetErr maps a transport-level error to a stable error code.
//
// This matters far more after the month-scan rewrite: the scan issues a long chain of
// /records/query calls, and if a transient timeout were classified as an auth failure the app
// would rebuild the session (a /validate round trip plus invalidation of every minted URL) on
// every hiccup.
func ClassifyNetErr(err error) string {
	if err == nil {
		return ""
	}

	// Preferred: structured inspection of the error chain.
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return ErrCodeTimeout
	}

	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return ErrCodeTimeout
	}

	// Fallback: the message text. go's http client wraps timeouts in *url.Error whose text
	// carries these phrases, and gomobile flattens everything to a string anyway.
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "context deadline exceeded") ||
		strings.Contains(msg, "client.timeout") ||
		strings.Contains(msg, "timeout") ||
		strings.Contains(msg, "i/o timeout") {
		return ErrCodeTimeout
	}

	return ErrCodeNetwork
}
