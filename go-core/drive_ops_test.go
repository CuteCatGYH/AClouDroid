package icloudcore

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCreateDriveFolderSuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/createFolders") {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{
				"folders": [
					{
						"drivewsid": "FOLDER::com.apple.CloudDocs::NEW-FOLDER-123",
						"docwsid": "NEW-FOLDER-123",
						"name": "Test Folder",
						"type": "FOLDER",
						"parentId": "FOLDER::com.apple.CloudDocs::root",
						"etag": "e1"
					}
				]
			}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer ts.Close()

	s := &Session{
		DrivewsURL: ts.URL,
		Client:     ts.Client(),
	}
	sessionMu.Lock()
	currentSession = s
	sessionMu.Unlock()

	respStr := CreateDriveFolder("FOLDER::com.apple.CloudDocs::root", "Test Folder")
	var res DriveOpResult
	if err := json.Unmarshal([]byte(respStr), &res); err != nil {
		t.Fatalf("Failed to unmarshal response: %v", err)
	}

	if !res.Success {
		t.Errorf("Expected success = true, got error: %s", res.ErrorMessage)
	}
	if len(res.Items) != 1 {
		t.Fatalf("Expected 1 item, got %d", len(res.Items))
	}
	if res.Items[0].Name != "Test Folder" {
		t.Errorf("Expected name 'Test Folder', got '%s'", res.Items[0].Name)
	}
}

func TestRenameDriveItemSuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/renameItems") {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{
				"items": [
					{
						"drivewsid": "FILE::123",
						"name": "Renamed.txt",
						"etag": "e2"
					}
				]
			}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer ts.Close()

	s := &Session{
		DrivewsURL: ts.URL,
		Client:     ts.Client(),
	}
	sessionMu.Lock()
	currentSession = s
	sessionMu.Unlock()

	respStr := RenameDriveItem("FILE::123", "e1", "Renamed.txt")
	var res DriveOpResult
	if err := json.Unmarshal([]byte(respStr), &res); err != nil {
		t.Fatalf("Failed to unmarshal response: %v", err)
	}

	if !res.Success {
		t.Errorf("Expected success = true, got error: %s", res.ErrorMessage)
	}
	if len(res.Items) != 1 || res.Items[0].Name != "Renamed.txt" {
		t.Errorf("Unexpected items: %+v", res.Items)
	}
}

func TestMoveDriveItemsSuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/moveItems") {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{
				"items": [
					{
						"drivewsid": "FILE::123",
						"parentId": "FOLDER::target",
						"etag": "e3"
					}
				]
			}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer ts.Close()

	s := &Session{
		DrivewsURL: ts.URL,
		Client:     ts.Client(),
	}
	sessionMu.Lock()
	currentSession = s
	sessionMu.Unlock()

	itemsJson := `[{"drivewsid": "FILE::123", "etag": "e1"}]`
	respStr := MoveDriveItems("FOLDER::target", itemsJson)
	var res DriveOpResult
	if err := json.Unmarshal([]byte(respStr), &res); err != nil {
		t.Fatalf("Failed to unmarshal response: %v", err)
	}

	if !res.Success {
		t.Errorf("Expected success = true, got error: %s", res.ErrorMessage)
	}
	if len(res.Items) != 1 {
		t.Fatalf("Expected 1 item, got %d", len(res.Items))
	}
}

func TestMoveDriveItemsToTrashSuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/moveItemsToTrash") {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{
				"items": [
					{
						"drivewsid": "FILE::123",
						"etag": "e4"
					}
				]
			}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer ts.Close()

	s := &Session{
		DrivewsURL: ts.URL,
		Client:     ts.Client(),
	}
	sessionMu.Lock()
	currentSession = s
	sessionMu.Unlock()

	itemsJson := `[{"drivewsid": "FILE::123", "etag": "e1"}]`
	respStr := MoveDriveItemsToTrash(itemsJson)
	var res DriveOpResult
	if err := json.Unmarshal([]byte(respStr), &res); err != nil {
		t.Fatalf("Failed to unmarshal response: %v", err)
	}

	if !res.Success {
		t.Errorf("Expected success = true, got error: %s", res.ErrorMessage)
	}
}
