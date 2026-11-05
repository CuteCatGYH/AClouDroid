package icloudcore

// Per-region single-file upload limits (ISS-057).
//
// This is NOT a protocol limit. The same 1.96 GB file, uploaded with byte-identical requests to
// `/upload/web` + `singleFileUpload`, succeeds on an icloud.com account and returns 413 on both
// icloud.com.cn (云上贵州) accounts tested. Since the client code path is identical, the ceiling
// lives in the account's region and cannot be raised from here.
//
// Consequence for this function: it must never impose a global cap. An international account
// uploading a 2.1 GB file is completely normal, and blocking it would be a regression.

import (
	"encoding/json"
	"strings"
)

// MainlandChinaSingleFileLimitBytes is the largest single-POST body accepted by the
// icloud.com.cn (云上贵州) backend.
//
// Measured on real transfers: 1024 MiB (1 GiB) completes, 1100 MiB returns 413.
const MainlandChinaSingleFileLimitBytes int64 = 1024 * 1024 * 1024

// UploadLimitInfo describes what the current account can upload in one request.
// isMainlandChinaHost reports whether the docws base URL points at the 云上贵州 backend.
//
// Matches the host suffix specifically — a bare `strings.Contains(u, ".cn")` would also match a
// path segment such as "/ws/cn" on an international host and wrongly apply the 1 GiB cap.
func isMainlandChinaHost(docsURL string) bool {
	if docsURL == "" {
		return false
	}
	// Strip scheme, then take the authority up to the first '/'.
	host := docsURL
	if i := strings.Index(host, "://"); i >= 0 {
		host = host[i+3:]
	}
	if i := strings.IndexAny(host, "/?"); i >= 0 {
		host = host[:i]
	}
	return strings.HasSuffix(strings.ToLower(host), ".icloud.com.cn")
}

type UploadLimitInfo struct {
	// MainlandChina is true when the account is served from the .com.cn backend.
	MainlandChina bool `json:"mainlandChina"`
	// SingleFileLimitBytes is 0 when the account has no known per-file ceiling.
	SingleFileLimitBytes int64 `json:"singleFileLimitBytes"`
	// DocsHost is the resolved docws host, useful for diagnosing region issues.
	DocsHost string `json:"docsHost"`
	// Reason is a short human-readable explanation.
	Reason string `json:"reason"`
}

// GetDriveUploadLimitInfo reports the current account's single-file upload ceiling.
//
// Callers should treat `SingleFileLimitBytes == 0` as "no restriction" and skip any pre-check.
func GetDriveUploadLimitInfo() string {
	info := UploadLimitInfo{}

	s := GetSession()
	if s == nil {
		info.Reason = "no active session"
		b, _ := json.Marshal(info)
		return string(b)
	}

	s.mu.RLock()
	docsURL := docsEndpointFromAccountInfo(s.AccountInfoJSON)
	s.mu.RUnlock()

	info.DocsHost = docsURL

	if docsURL == "" {
		info.Reason = "docws endpoint unavailable; assuming no limit rather than blocking the user"
		b, _ := json.Marshal(info)
		return string(b)
	}

	// The region is inferred from the host, not from a locale setting: the ceiling follows the
	// backend that serves the account, and .com.cn is the identifier that actually correlates
	// with the 413 behaviour observed in the captures.
	if isMainlandChinaHost(docsURL) {
		info.MainlandChina = true
		info.SingleFileLimitBytes = MainlandChinaSingleFileLimitBytes
		info.Reason = "icloud.com.cn (云上贵州) enforces a 1 GiB single-file upload limit"
	} else {
		info.Reason = "international backend; no known single-file upload limit"
	}

	b, _ := json.Marshal(info)
	return string(b)
}
