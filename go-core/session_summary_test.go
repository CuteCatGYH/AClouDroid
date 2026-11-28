package icloudcore

import (
	"encoding/json"
	"net/http"
	"sync"
	"testing"
	"time"
)

// The summary must distinguish "session has drivews" from "session can also transfer".
//
// Uploads resolve their endpoint from `webservices.docws`. A session with drivews but no docws
// passed the old readiness guard, so the uploader started and immediately failed with
// `AUTH_EXPIRED: docws endpoint is not set` — a terminal error, meaning a queued file was lost
// rather than retried. Exposing docwsURL lets the caller wait instead of failing.
func TestSessionStateSummaryExposesDocsEndpoint(t *testing.T) {
	session := &Session{
		DrivewsURL: "https://p203-drivews.icloud.com.cn",
		AccountInfoJSON: `{"webservices":{` +
			`"drivews":{"url":"https://p203-drivews.icloud.com.cn:443"},` +
			`"docws":{"url":"https://p203-docws.icloud.com.cn:443"}}}`,
		CookieMap: map[string]string{"X-APPLE-WEBAUTH-TOKEN": "token"},
	}
	currentSession = session
	t.Cleanup(func() { currentSession = nil })

	var summary map[string]interface{}
	if err := json.Unmarshal([]byte(GetSessionStateSummary()), &summary); err != nil {
		t.Fatalf("summary is not valid JSON: %v", err)
	}

	if got := summary["docwsURL"]; got != "https://p203-docws.icloud.com.cn" {
		t.Errorf("docwsURL = %v, want the normalised docws endpoint", got)
	}
	if got := summary["hasDocsEndpoint"]; got != true {
		t.Errorf("hasDocsEndpoint = %v, want true", got)
	}
}

// A session without docws must report the endpoint as absent, not merely omit the key: the
// caller branches on the value.
func TestSessionStateSummaryReportsMissingDocsEndpoint(t *testing.T) {
	session := &Session{
		DrivewsURL:      "https://p203-drivews.icloud.com.cn",
		AccountInfoJSON: `{"webservices":{"drivews":{"url":"https://p203-drivews.icloud.com.cn:443"}}}`,
		CookieMap:       map[string]string{"X-APPLE-WEBAUTH-TOKEN": "token"},
	}
	currentSession = session
	t.Cleanup(func() { currentSession = nil })

	var summary map[string]interface{}
	if err := json.Unmarshal([]byte(GetSessionStateSummary()), &summary); err != nil {
		t.Fatalf("summary is not valid JSON: %v", err)
	}

	if got := summary["docwsURL"]; got != "" {
		t.Errorf("docwsURL = %v, want empty", got)
	}
	if got := summary["hasDocsEndpoint"]; got != false {
		t.Errorf("hasDocsEndpoint = %v, want false", got)
	}
}

// Regression test: GetSessionStateSummary must never recursively acquire s.mu.RLock().
// Running it concurrently with ExtractAndSaveCookies (which acquires s.mu.Lock()) used to
// deadlock Go's sync.RWMutex permanently once a writer queued between the outer and inner RLock().
func TestGetSessionStateSummaryNoDeadlockWithConcurrentCookieWrites(t *testing.T) {
	s := newTestSession(t)
	s.CookieMap["X-APPLE-WEBAUTH-TOKEN"] = "token"
	installSessionForTest(t, s)

	resp := &http.Response{
		Header: http.Header{
			"Set-Cookie": []string{"X-APPLE-WEBAUTH-TOKEN=rotated; Path=/"},
		},
	}

	done := make(chan struct{})
	go func() {
		var wg sync.WaitGroup
		for i := 0; i < 4; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for j := 0; j < 200; j++ {
					_ = GetSessionStateSummary()
				}
			}()
			wg.Add(1)
			go func() {
				defer wg.Done()
				for j := 0; j < 200; j++ {
					s.ExtractAndSaveCookies(resp)
				}
			}()
		}
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("GetSessionStateSummary deadlocked with concurrent ExtractAndSaveCookies")
	}
}
