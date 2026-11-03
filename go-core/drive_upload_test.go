package icloudcore

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestUploadDriveItemGuards(t *testing.T) {
	currentSession = nil
	resStr := UploadDriveItemByPath("/nonexistent/file.txt", "test.txt", "text/plain", "/")
	var res DriveUploadResult
	if err := json.Unmarshal([]byte(resStr), &res); err != nil {
		t.Fatalf("Failed to parse result JSON: %v", err)
	}
	if res.Success {
		t.Errorf("Expected failure when no file exists, got success")
	}

	tmp, err := os.CreateTemp("", "test_upload_*.txt")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	defer os.Remove(tmp.Name())
	tmp.WriteString("Hello Go upload test")
	tmp.Close()

	resNoSession := UploadDriveItemByPath(tmp.Name(), "test.txt", "text/plain", "/")
	var res2 DriveUploadResult
	if err := json.Unmarshal([]byte(resNoSession), &res2); err != nil {
		t.Fatalf("Failed to parse result JSON: %v", err)
	}
	if res2.Success {
		t.Errorf("Expected failure when no session exists, got success")
	}
	if res2.ErrorCode != ErrCodeAuthExpired {
		t.Errorf("Expected ErrCodeAuthExpired, got %s", res2.ErrorCode)
	}
}

func TestUploadDriveItemMockFlow(t *testing.T) {
	mux := http.NewServeMux()

	mux.HandleFunc("/ws/com.apple.CloudDocs/upload/web", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`[{"document_id":"DOC-UPLOAD-100","url":"` + "http://" + r.Host + `/singleFileUpload?tk=test"}]`))
	})

	mux.HandleFunc("/singleFileUpload", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"singleFile":{"fileChecksum":"CHECKSUM-100"}}`))
	})

	mux.HandleFunc("/ws/com.apple.CloudDocs/update/documents", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"status":{"status_code":0,"error_message":"OK"},"results":[{"status":{"status_code":0,"error_message":"OK"},"document":{"document_id":"DOC-UPLOAD-100"}}]}`))
	})

	ts := httptest.NewServer(mux)
	defer ts.Close()

	session := &Session{
		DrivewsURL:      ts.URL,
		AccountInfoJSON: fmt.Sprintf(`{"webservices":{"docws":{"url":"%s"},"drivews":{"url":"%s"}}}`, ts.URL, ts.URL),
		CookieMap:       map[string]string{"X-APPLE-WEBAUTH-TOKEN": "token"},
		Client:          ts.Client(),
	}
	currentSession = session

	tmp, err := os.CreateTemp("", "test_upload_file_*.txt")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	defer os.Remove(tmp.Name())
	tmp.WriteString("Unit test upload file payload")
	tmp.Close()

	resStr := UploadDriveItemByPath(tmp.Name(), filepath.Base(tmp.Name()), "text/plain", "/Documents")
	var res DriveUploadResult
	if err := json.Unmarshal([]byte(resStr), &res); err != nil {
		t.Fatalf("Failed to parse result JSON: %v", err)
	}

	if !res.Success {
		t.Fatalf("Expected success true, got false: %s", res.ErrorMessage)
	}

	if res.DocumentID != "DOC-UPLOAD-100" {
		t.Errorf("Expected DocumentID DOC-UPLOAD-100, got %s", res.DocumentID)
	}

	if res.DrivewsID != "FILE::com.apple.CloudDocs::DOC-UPLOAD-100" {
		t.Errorf("Expected DrivewsID FILE::com.apple.CloudDocs::DOC-UPLOAD-100, got %s", res.DrivewsID)
	}

	if res.FileChecksum != "CHECKSUM-100" {
		t.Errorf("Expected FileChecksum CHECKSUM-100, got %s", res.FileChecksum)
	}
}
