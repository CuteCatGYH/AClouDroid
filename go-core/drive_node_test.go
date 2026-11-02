package icloudcore

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The whole reason this endpoint has its own function is the wrapper/array difference. If the
// body shape regresses, the server answers a bare 400 and every incremental sync pass silently
// degrades to full listing — so the request body is asserted explicitly.
func TestRetrieveItemDetailsSendsWrappedBody(t *testing.T) {
	var gotBody string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"items":[{"drivewsid":"FOLDER::com.apple.CloudDocs::root","etag":"nrp","type":"FOLDER","directChildrenCount":35}]}`))
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL

	RetrieveItemDetails("FOLDER::com.apple.CloudDocs::root")

	if !strings.HasPrefix(strings.TrimSpace(gotBody), "{") {
		t.Fatalf("body must be a wrapped object, got: %s", gotBody)
	}
	var wrapper map[string]json.RawMessage
	if err := json.Unmarshal([]byte(gotBody), &wrapper); err != nil {
		t.Fatalf("body is not valid JSON: %v (%s)", err, gotBody)
	}
	if _, ok := wrapper["items"]; !ok {
		t.Fatalf("body must carry an `items` key, got: %s", gotBody)
	}
}

func TestRetrieveItemDetailsSuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/retrieveItemDetails") {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"items":[{"drivewsid":"FOLDER::com.apple.CloudDocs::D2D2","docwsid":"D2D2","name":"Desktop","etag":"hnz","type":"FOLDER","directChildrenCount":19,"fileCount":19}]}`))
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL

	var res DriveNodeResult
	if err := json.Unmarshal([]byte(RetrieveItemDetails("FOLDER::com.apple.CloudDocs::D2D2")), &res); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if !res.Success {
		t.Fatalf("expected success, got %+v", res)
	}
	if res.Item == nil {
		t.Fatal("expected a node, got nil")
	}
	if res.Item.Etag != "hnz" {
		t.Errorf("expected etag hnz, got %q", res.Item.Etag)
	}
	if res.Item.DirectChildrenCount != 19 {
		t.Errorf("expected 19 children, got %d", res.Item.DirectChildrenCount)
	}
}

// An empty `items` array is how the server reports a node that no longer exists. Mapping it to
// NOT_FOUND lets the caller drop the row instead of retrying a deleted folder forever.
func TestRetrieveItemDetailsEmptyItemsIsNotFound(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"items":[]}`))
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL

	var res DriveNodeResult
	if err := json.Unmarshal([]byte(RetrieveItemDetails("FOLDER::com.apple.CloudDocs::gone")), &res); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if res.Success {
		t.Fatal("empty items must not be reported as success")
	}
	if res.ErrorCode != ErrCodeNotFound {
		t.Errorf("expected %s, got %q", ErrCodeNotFound, res.ErrorCode)
	}
}

func TestRetrieveItemDetailsHTTP401(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"errorReason":"Unauthorized","errorCode":401}`))
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL

	var res DriveNodeResult
	if err := json.Unmarshal([]byte(RetrieveItemDetails("root")), &res); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if res.Success {
		t.Fatal("401 must not be reported as success")
	}
	if res.HttpStatus != 401 {
		t.Errorf("expected HttpStatus 401, got %d", res.HttpStatus)
	}
}

func TestRetrieveItemDetailsNoSession(t *testing.T) {
	// Ensure no leftover session from another test makes this pass by accident.
	resetSessionForTest()

	var res DriveNodeResult
	if err := json.Unmarshal([]byte(RetrieveItemDetails("root")), &res); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if res.Success {
		t.Fatal("expected failure without a session")
	}
	if res.ErrorCode != ErrCodeAuthExpired {
		t.Errorf("expected %s, got %q", ErrCodeAuthExpired, res.ErrorCode)
	}
}
