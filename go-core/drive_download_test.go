package icloudcore

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newDownloadSession builds a session whose account info advertises `docws` at the test server.
func newDownloadSession(t *testing.T, docsURL string) *Session {
	t.Helper()
	resetSessionForTest()
	t.Cleanup(resetSessionForTest)

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.AccountInfoJSON = `{"webservices":{"docws":{"url":"` + docsURL + `"}}}`
	return session
}

func TestRetrieveDownloadURLSuccess(t *testing.T) {
	var gotPath, gotQuery string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"document_id":"AF01D500-FCB8",
			"item_id":"CKH_iPlKEAAiEK8B",
			"owner_dsid":20118126497,
			"data_token":{"url":"https://cvws.example.com/file.heic?o=abc"},
			"thumbnail_token":{"url":"https://cvws.example.com/thumb.heic?o=def"}
		}`))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	var res DriveDownloadTokenResult
	if err := json.Unmarshal([]byte(RetrieveDownloadURL("AF01D500-FCB8")), &res); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !res.Success {
		t.Fatalf("expected success, got %+v", res.ResultBase)
	}

	// The endpoint path must carry the default zone.
	if gotPath != "/ws/com.apple.CloudDocs/download/by_id" {
		t.Errorf("path = %q, want /ws/com.apple.CloudDocs/download/by_id", gotPath)
	}
	if !strings.Contains(gotQuery, "document_id=AF01D500-FCB8") {
		t.Errorf("query = %q, want document_id", gotQuery)
	}

	if res.Token == nil {
		t.Fatal("token is nil")
	}
	if res.Token.URL != "https://cvws.example.com/file.heic?o=abc" {
		t.Errorf("URL = %q", res.Token.URL)
	}
	if res.Token.ThumbnailURL != "https://cvws.example.com/thumb.heic?o=def" {
		t.Errorf("ThumbnailURL = %q", res.Token.ThumbnailURL)
	}
	if res.Token.OwnerDSID != 20118126497 {
		t.Errorf("OwnerDSID = %d", res.Token.OwnerDSID)
	}
}

// A fully-qualified drivewsid must be reduced to the bare uuid, because `document_id` rejects the
// prefixed form. Callers legitimately hold either shape.
func TestRetrieveDownloadURLAcceptsFullyQualifiedID(t *testing.T) {
	var gotQuery string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data_token":{"url":"https://cvws.example.com/f"}}`))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	RetrieveDownloadURL("FILE::com.apple.CloudDocs::AF01D500-FCB8")

	if !strings.Contains(gotQuery, "document_id=AF01D500-FCB8") {
		t.Errorf("query = %q, want the bare uuid without the FILE:: prefix", gotQuery)
	}
}

// An APP_LIBRARY file lives in the app's own zone; the endpoint path must reflect that or the
// server answers 404 for a file that plainly exists.
func TestRetrieveDownloadURLUsesZoneFromID(t *testing.T) {
	var gotPath string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data_token":{"url":"https://cvws.example.com/f"}}`))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	RetrieveDownloadURL("FILE::com.apple.Keynote::AAAA-BBBB")

	if gotPath != "/ws/com.apple.Keynote/download/by_id" {
		t.Errorf("path = %q, want the zone from the id", gotPath)
	}
}

// A 200 with no data_token.url means "nothing downloadable" (an iWork bundle, a placeholder).
// Surfacing that as NOT_FOUND beats handing back an empty URL the caller then fails on.
func TestRetrieveDownloadURLMissingDataToken(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"document_id":"x","thumbnail_token":{"url":"https://t"}}`))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	var res DriveDownloadTokenResult
	json.Unmarshal([]byte(RetrieveDownloadURL("x")), &res)

	if res.Success {
		t.Fatal("missing data_token.url must not be success")
	}
	if res.ErrorCode != ErrCodeNotFound {
		t.Errorf("errorCode = %q, want %s", res.ErrorCode, ErrCodeNotFound)
	}
}

func TestRetrieveDownloadURLUnauthorized(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"errorReason":"Unauthorized"}`))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	var res DriveDownloadTokenResult
	json.Unmarshal([]byte(RetrieveDownloadURL("x")), &res)

	if res.ErrorCode != ErrCodeAuthExpired {
		t.Errorf("errorCode = %q, want %s", res.ErrorCode, ErrCodeAuthExpired)
	}
	if res.HttpStatus != 401 {
		t.Errorf("httpStatus = %d", res.HttpStatus)
	}
}

// ---- DownloadToFile -------------------------------------------------------------------

func TestDownloadToFileWritesContent(t *testing.T) {
	payload := strings.Repeat("icloudroid", 1000)
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The signed URL must be fetched WITHOUT session cookies — a CDN rejects them.
		if len(r.Cookies()) != 0 {
			t.Errorf("download request carried cookies: %v", r.Cookies())
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(payload))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)
	dest := filepath.Join(t.TempDir(), "out.bin")

	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFile(ts.URL+"/file", dest, 0)), &res)

	if !res.Success {
		t.Fatalf("expected success, got %+v", res.ResultBase)
	}
	if res.Written != int64(len(payload)) {
		t.Errorf("written = %d, want %d", res.Written, len(payload))
	}
	got, err := os.ReadFile(dest)
	if err != nil {
		t.Fatalf("read dest: %v", err)
	}
	if string(got) != payload {
		t.Error("file content mismatch")
	}
}

// The CDN rejects a signed URL that arrives with an iCloud `Origin` / `Referer`, answering
// `503 Bad Gateway`. Those headers are exactly what `Session.NewRequest` stamps onto every
// request, so the download path must build its own.
//
// This is the regression guard for the bug that made every Drive download fail while the unit
// tests still passed: the tests used to ignore headers entirely.
func TestDownloadToFileSendsNoOriginOrReferer(t *testing.T) {
	var origin, referer, cookie string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin = r.Header.Get("Origin")
		referer = r.Header.Get("Referer")
		cookie = r.Header.Get("Cookie")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer ts.Close()

	session := newDownloadSession(t, ts.URL)
	// Give the session a cookie jar and browser-ish defaults, so a regression that routes through
	// `s.NewRequest` is actually reproduced rather than merely described.
	session.Origin = "https://www.icloud.com"
	session.Referer = "https://www.icloud.com/"

	dest := filepath.Join(t.TempDir(), "out.bin")
	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFile(ts.URL+"/f", dest, 0)), &res)

	if !res.Success {
		t.Fatalf("expected success, got %+v", res.ResultBase)
	}
	if origin != "" {
		t.Errorf("Origin = %q, must be empty; the CDN answers 503 when it is set", origin)
	}
	if referer != "" {
		t.Errorf("Referer = %q, must be empty; the CDN answers 503 when it is set", referer)
	}
	if cookie != "" {
		t.Errorf("Cookie = %q, must be empty; the signature is the only credential accepted", cookie)
	}
}

func TestDownloadToFileResumesWithRange(t *testing.T) {
	full := "0123456789"
	var gotRange string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRange = r.Header.Get("Range")
		w.WriteHeader(http.StatusPartialContent)
		w.Write([]byte(full[4:]))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	dir := t.TempDir()
	dest := filepath.Join(dir, "out.bin")
	if err := os.WriteFile(dest, []byte(full[:4]), 0o644); err != nil {
		t.Fatalf("seed partial: %v", err)
	}

	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFile(ts.URL+"/file", dest, 4)), &res)

	if !res.Success {
		t.Fatalf("expected success, got %+v", res.ResultBase)
	}
	if gotRange != "bytes=4-" {
		t.Errorf("Range = %q, want bytes=4-", gotRange)
	}
	got, _ := os.ReadFile(dest)
	if string(got) != full {
		t.Errorf("content = %q, want %q", string(got), full)
	}
}

// If the server ignores the Range and sends the whole file, appending would produce a corrupt
// result. Refusing is the only safe outcome.
func TestDownloadToFileRefusesResumeWhenRangeIgnored(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK) // not 206
		w.Write([]byte("0123456789"))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)

	dest := filepath.Join(t.TempDir(), "out.bin")
	os.WriteFile(dest, []byte("0123"), 0o644)

	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFile(ts.URL+"/file", dest, 4)), &res)

	if res.Success {
		t.Fatal("a 200 on a resumed download must be refused")
	}
	got, _ := os.ReadFile(dest)
	if string(got) != "0123" {
		t.Errorf("partial file must be untouched, got %q", string(got))
	}
}

func TestDownloadToFileHTTPError(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("File not found"))
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)
	dest := filepath.Join(t.TempDir(), "out.bin")

	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFile(ts.URL+"/file", dest, 0)), &res)

	if res.Success {
		t.Fatal("404 must not be success")
	}
	if res.ErrorCode != ErrCodeNotFound {
		t.Errorf("errorCode = %q, want %s", res.ErrorCode, ErrCodeNotFound)
	}
}

func TestDownloadToFileEmptyURL(t *testing.T) {
	newDownloadSession(t, "https://example.invalid")

	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFile("", filepath.Join(t.TempDir(), "x"), 0)), &res)

	if res.Success {
		t.Fatal("empty URL must not be success")
	}
}

func TestDownloadToFileCancellation(t *testing.T) {
	started := make(chan struct{})
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		// Block until context cancellation closes connection
		<-r.Context().Done()
	}))
	defer ts.Close()

	newDownloadSession(t, ts.URL)
	dest := filepath.Join(t.TempDir(), "out.bin")

	go func() {
		<-started
		CancelDownload("test-doc-id")
	}()

	var res DriveDownloadResult
	json.Unmarshal([]byte(DownloadToFileWithID(ts.URL+"/file", dest, 0, "test-doc-id")), &res)

	if res.Success {
		t.Fatal("cancelled download must not succeed")
	}
	if !strings.Contains(res.ErrorMessage, "cancelled") {
		t.Errorf("errorMessage = %q, want cancelled", res.ErrorMessage)
	}
}
