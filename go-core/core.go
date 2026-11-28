package icloudcore

import (
	"encoding/json"
)

// GetVersion returns the version of Go core layer
func GetVersion() string {
	return "0.1.0-alpha"
}

// Ping returns a simple pong message for smoke testing JNI linkage
func Ping(name string) string {
	return "Hello from Go core, " + name + "!"
}

// GetSessionStateSummary returns a sanitized diagnostic summary of the current session state.
func GetSessionStateSummary() string {
	s := GetSession()
	if s == nil {
		return "No active session in Go core"
	}
	s.mu.RLock()
	cookieKeys := make([]string, 0, len(s.CookieMap))
	for k := range s.CookieMap {
		cookieKeys = append(cookieKeys, k)
	}
	isChina := s.IsChinaMainland
	baseURL := s.BaseURL
	dsid := s.DSID
	ckDatabaseURL := s.CKDatabaseURL
	drivewsURL := s.DrivewsURL
	accountInfoJSON := s.AccountInfoJSON
	cookieCount := len(s.CookieMap)
	// Read CookieMap via the locked helper rather than HasCookieIgnoreCase(), which re-acquires
	// s.mu.RLock(). Recursive RLock deadlocks in Go's sync.RWMutex whenever a concurrent
	// ExtractAndSaveCookies() calls s.mu.Lock() in between.
	hasAuthToken := s.hasCookieIgnoreCaseLocked("X-APPLE-WEBAUTH-TOKEN")
	hasHsaTrust := s.hasCookieIgnoreCaseLocked("X-APPLE-WEBAUTH-HSA-TRUST")
	hasPcsCloudKit := s.hasCookieIgnoreCaseLocked("X-APPLE-WEBAUTH-PCS-CloudKit")
	hasPcsPhotos := s.hasCookieIgnoreCaseLocked("X-APPLE-WEBAUTH-PCS-Photos")
	s.mu.RUnlock()

	// docwsURL is the CloudDocs transfer endpoint. It is exposed separately from drivewsURL
	// because uploads resolve it from `webservices.docws` and fail with
	// `AUTH_EXPIRED: docws endpoint is not set` when it is empty — a terminal error the caller
	// cannot retry. Kotlin needs to see both fields to tell "session ready" apart from
	// "session has drivews but is not yet usable for transfer".
	docsURL := docsEndpointFromAccountInfo(accountInfoJSON)

	summary := map[string]interface{}{
		"isChina":          isChina,
		"baseURL":          baseURL,
		"dsid":             dsid,
		"ckDatabaseURL":    ckDatabaseURL,
		"drivewsURL":       drivewsURL,
		"docwsURL":         docsURL,
		"hasDriveEndpoint": drivewsURL != "",
		"hasDocsEndpoint":  docsURL != "",
		"cookieCount":      cookieCount,
		"cookieNames":      cookieKeys,
		"hasAuthToken":     hasAuthToken,
		"hasHsaTrust":      hasHsaTrust,
		"hasPcsCloudKit":   hasPcsCloudKit,
		"hasPcsPhotos":     hasPcsPhotos,
	}
	bytes, _ := json.Marshal(summary)
	return string(bytes)
}

// GetAccountInfoJSON returns the raw JSON from validate/accountLogin (contains webservices dict).
func GetAccountInfoJSON() string {
	s := GetSession()
	if s == nil {
		return "{}"
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.AccountInfoJSON == "" {
		return "{}"
	}
	return s.AccountInfoJSON
}

// InitSession initializes a new iCloud session with cookie string and region setting.
// Returns a JSON string containing SessionInitResult.
func InitSession(cookieStr string, isChinaMainland bool) string {
	sessionMu.Lock()
	defer sessionMu.Unlock()

	session, err := NewSession(cookieStr, isChinaMainland)
	if err != nil {
		res, _ := json.Marshal(SessionInitResult{
			Success:      false,
			ErrorMessage: err.Error(),
			IsChina:      isChinaMainland,
		})
		return string(res)
	}

	result, err := session.ValidateSession()
	if err != nil {
		res, _ := json.Marshal(SessionInitResult{
			Success:      false,
			ErrorMessage: err.Error(),
			IsChina:      isChinaMainland,
		})
		return string(res)
	}

	// Persist the routing information on the session itself.
	//
	// Without this the session object holds no `DSID` / `CKDatabaseURL`, so `photosBaseURL()`
	// builds a URL with an empty host — every photo call then fails with
	// `unsupported protocol scheme ""`. It went unnoticed because the Kotlin layer reads these
	// values from InitSession's *return value* and persists them to its own encrypted store;
	// only in-process callers (diagnostics, and anything running before Kotlin re-primes the
	// session) ever hit it.
	session.mu.Lock()
	if result.DSID != "" {
		session.DSID = result.DSID
	}
	if result.CKDatabaseURL != "" {
		session.CKDatabaseURL = result.CKDatabaseURL
	}
	if result.DrivewsURL != "" {
		session.DrivewsURL = result.DrivewsURL
	}
	session.mu.Unlock()

	currentSession = session

	resBytes, _ := json.Marshal(result)
	return string(resBytes)
}
