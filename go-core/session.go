package icloudcore

import (
	"crypto/tls"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	ChinaMainlandBaseURL = "https://setup.icloud.com.cn"
	GlobalBaseURL        = "https://setup.icloud.com"

	ChinaMainlandOrigin  = "https://www.icloud.com.cn"
	ChinaMainlandReferer = "https://www.icloud.com.cn/"

	GlobalOrigin  = "https://www.icloud.com"
	GlobalReferer = "https://www.icloud.com/"

	DefaultUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)

type Session struct {
	mu              sync.RWMutex
	Client          *http.Client
	IsChinaMainland bool
	BaseURL         string
	Origin          string
	Referer         string
	Domain          string

	// Raw and parsed cookies
	CookieStr      string
	CookieMap      map[string]string
	UpdatedCookies bool

	// Extracted credentials
	DSID          string
	CKDatabaseURL string
	// DrivewsURL is the CloudDocs (`drivews`) endpoint from /validate, used by the Drive
	// feature (Phase 5). It lives here rather than in a Drive-only struct for the same reason
	// CKDatabaseURL does: any in-process caller must be able to build a Drive URL without a
	// round trip through Kotlin (ISS-029).
	DrivewsURL      string
	AccountInfoJSON string

	// ClientID identifies this client to CloudKit. It must be stable for the whole session:
	// the continuationMarker cursor and the paginated /records/query chain are bound to it
	// (ISS-014). Minting a new one per request made the server treat each page as a
	// different client.
	ClientID string
}

var currentSession *Session
var sessionMu sync.Mutex

func GetSession() *Session {
	sessionMu.Lock()
	defer sessionMu.Unlock()
	return currentSession
}

// GetCurrentCookieString returns the latest formatted cookie string from active session.
// Allows Kotlin to poll or update its local encrypted storage after silent refresh.
func GetCurrentCookieString() string {
	s := GetSession()
	if s == nil {
		return ""
	}
	return s.BuildCookieHeader()
}

// HasCookieUpdates returns whether new cookies were obtained since last check.
func HasCookieUpdates() bool {
	s := GetSession()
	if s == nil {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	has := s.UpdatedCookies
	s.UpdatedCookies = false
	return has
}

func NewSession(cookieStr string, isChinaMainland bool) (*Session, error) {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create cookiejar: %w", err)
	}

	baseURL := GlobalBaseURL
	origin := GlobalOrigin
	referer := GlobalReferer
	domain := ".icloud.com"
	if isChinaMainland {
		baseURL = ChinaMainlandBaseURL
		origin = ChinaMainlandOrigin
		referer = ChinaMainlandReferer
		domain = ".icloud.com.cn"
	}

	u, err := url.Parse(baseURL)
	if err != nil {
		return nil, fmt.Errorf("failed to parse base url: %w", err)
	}

	// Parse raw cookie string and strip surrounding quotes
	cookieMap := make(map[string]string)
	var cookies []*http.Cookie
	parts := strings.Split(cookieStr, ";")
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		// Skip RFC 2965 special attribute headers
		if strings.HasPrefix(part, "$") {
			continue
		}
		kv := strings.SplitN(part, "=", 2)
		if len(kv) == 2 {
			k := strings.TrimSpace(kv[0])
			v := strings.Trim(strings.TrimSpace(kv[1]), "\"")
			cookieMap[k] = v
			cookies = append(cookies, &http.Cookie{
				Name:   k,
				Value:  v,
				Domain: domain,
				Path:   "/",
			})
		}
	}
	jar.SetCookies(u, cookies)

	client := &http.Client{
		Jar:     jar,
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: false},
		},
	}

	s := &Session{
		Client:          client,
		IsChinaMainland: isChinaMainland,
		BaseURL:         baseURL,
		Origin:          origin,
		Referer:         referer,
		Domain:          domain,
		CookieStr:       cookieStr,
		CookieMap:       cookieMap,
		// One ClientID per session (ISS-014).
		ClientID: generateClientID(),
	}

	return s, nil
}

// ExtractAndSaveCookies updates the internal CookieMap and jar with cookies from HTTP response headers.
func (s *Session) ExtractAndSaveCookies(resp *http.Response) {
	if resp == nil {
		return
	}
	rawCookies := resp.Cookies()
	if len(rawCookies) == 0 {
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	hasChanges := false
	for _, c := range rawCookies {
		val := strings.Trim(strings.TrimSpace(c.Value), "\"")
		if oldVal, exists := s.CookieMap[c.Name]; !exists || oldVal != val {
			s.CookieMap[c.Name] = val
			hasChanges = true
		}
	}
	if hasChanges {
		s.UpdatedCookies = true
	}
}

// BuildCookieHeader formats cookies in CookieMap into a standard HTTP Cookie header.
//
// The keys MUST be sorted. Go deliberately randomises `map` iteration order, so the naive
// `for k, v := range s.CookieMap` produced a *different string* for the same cookie set on
// every call. Kotlin compares the persisted cookie string with the freshly serialised one to
// decide whether credentials rotated, so that non-determinism made every cold start look like
// a rotation: `Cookies rotated (len 2893 -> 2893)`, followed by a session-generation bump that
// marked the entire cached library stale. Sorting makes the serialisation canonical, which
// also makes persisted cookie strings diffable in logs and stable across process restarts.
func (s *Session) BuildCookieHeader() string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	keys := make([]string, 0, len(s.CookieMap))
	for k := range s.CookieMap {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var b strings.Builder
	for i, k := range keys {
		if i > 0 {
			b.WriteString("; ")
		}
		b.WriteString(k)
		b.WriteString("=")
		b.WriteString(s.CookieMap[k])
	}
	return b.String()
}

func (s *Session) NewRequest(method, reqURL string, body string) (*http.Request, error) {
	var bodyReader *strings.Reader
	if body != "" {
		bodyReader = strings.NewReader(body)
	} else {
		bodyReader = strings.NewReader("")
	}

	req, err := http.NewRequest(method, reqURL, bodyReader)
	if err != nil {
		return nil, err
	}

	req.Header.Set("User-Agent", DefaultUserAgent)
	req.Header.Set("Origin", s.Origin)
	req.Header.Set("Referer", s.Referer)
	req.Header.Set("Accept", "*/*")
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}

	// Explicitly inject Cookie header to guarantee cross-subdomain compatibility
	// (e.g. pXX-ckdatabasews.icloud.com[.cn], cvws.icloud-content.com[.cn])
	cookieHeader := s.BuildCookieHeader()
	if cookieHeader != "" {
		req.Header.Set("Cookie", cookieHeader)
	}

	return req, nil
}

// HasCookieIgnoreCase performs a case-insensitive check for a cookie name in CookieMap.
func (s *Session) HasCookieIgnoreCase(name string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for k := range s.CookieMap {
		if strings.EqualFold(k, name) {
			return true
		}
	}
	return false
}
