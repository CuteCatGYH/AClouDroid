package icloudcore

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRetrieveItemDetailsInFoldersSuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/retrieveItemDetailsInFolders") {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`[
				{
					"dateCreated": "2026-02-05T05:57:15Z",
					"drivewsid": "FOLDER::com.apple.CloudDocs::root",
					"docwsid": "root",
					"zone": "com.apple.CloudDocs",
					"name": "",
					"etag": "nrp",
					"type": "FOLDER",
					"directChildrenCount": 3,
					"items": [
						{
							"dateCreated": "2026-04-06T13:25:03Z",
							"drivewsid": "FILE::com.apple.CloudDocs::2ABAAD23-9AB9-4615-ACB0-428594E87AB2",
							"docwsid": "2ABAAD23-9AB9-4615-ACB0-428594E87AB2",
							"zone": "com.apple.CloudDocs",
							"name": "sample.mp4",
							"extension": "mp4",
							"parentId": "FOLDER::com.apple.CloudDocs::root",
							"item_id": "CKH_sample",
							"dateModified": "2026-04-06T13:25:03Z",
							"dateChanged": "2026-04-26T09:59:17Z",
							"size": 2540192,
							"etag": "gqp::gm9",
							"type": "FILE"
						},
						{
							"dateCreated": "2026-02-05T05:57:15Z",
							"drivewsid": "FOLDER::com.apple.CloudDocs::D2D2D2D2-D2D2-D2D2-D2D2-D2D2D2D2D2D2",
							"docwsid": "D2D2D2D2-D2D2-D2D2-D2D2-D2D2D2D2D2D2",
							"zone": "com.apple.CloudDocs",
							"name": "Documents",
							"parentId": "FOLDER::com.apple.CloudDocs::root",
							"etag": "npq",
							"type": "FOLDER",
							"directChildrenCount": 4
						},
						{
							"dateCreated": "2025-09-07T13:00:14Z",
							"drivewsid": "FOLDER::iCloud.com.chii.idays.storage::documents",
							"docwsid": "documents",
							"zone": "iCloud.com.chii.idays.storage",
							"name": "Days Matter",
							"parentId": "FOLDER::com.apple.CloudDocs::root",
							"etag": "b6",
							"type": "APP_LIBRARY",
							"icons": [
								{
									"url": "https://p203-drivews.icloud.com.cn/getIcons?id=iCloud.com.chii.idays.storage&field=icon120x120_iOS",
									"type": "IOS",
									"size": 120
								}
							]
						}
					]
				}
			]`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL
	session.DSID = "12345678"

	resJSON := RetrieveItemDetailsInFolders("root")
	var res DriveFolderListResult
	if err := json.Unmarshal([]byte(resJSON), &res); err != nil {
		t.Fatalf("failed to unmarshal result: %v", err)
	}

	if !res.Success {
		t.Fatalf("expected success, got %+v", res)
	}
	if res.DrivewsID != "FOLDER::com.apple.CloudDocs::root" {
		t.Errorf("expected normalized drivewsid 'FOLDER::com.apple.CloudDocs::root', got %s", res.DrivewsID)
	}
	if len(res.Items) != 3 {
		t.Fatalf("expected 3 items, got %d", len(res.Items))
	}

	// Verify FILE item
	item0 := res.Items[0]
	if item0.Type != "FILE" || item0.Name != "sample.mp4" || item0.Extension != "mp4" || item0.Size != 2540192 || item0.ItemID != "CKH_sample" {
		t.Errorf("item0 mismatch: %+v", item0)
	}

	// Verify FOLDER item
	item1 := res.Items[1]
	if item1.Type != "FOLDER" || item1.Name != "Documents" || item1.DirectChildrenCount != 4 {
		t.Errorf("item1 mismatch: %+v", item1)
	}

	// Verify APP_LIBRARY item
	item2 := res.Items[2]
	if item2.Type != "APP_LIBRARY" || item2.Name != "Days Matter" || len(item2.Icons) != 1 || item2.Icons[0].Size != 120 {
		t.Errorf("item2 mismatch: %+v", item2)
	}
}

func TestRetrieveItemDetailsInFoldersHTTP401(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"errorReason": "Unauthorized", "errorCode": 401}`))
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL

	resJSON := RetrieveItemDetailsInFolders("FOLDER::com.apple.CloudDocs::root")
	var res DriveFolderListResult
	if err := json.Unmarshal([]byte(resJSON), &res); err != nil {
		t.Fatalf("failed to unmarshal result: %v", err)
	}

	if res.Success {
		t.Fatalf("expected failure on 401, got success")
	}
	if res.ErrorCode != ErrCodeAuthExpired {
		t.Errorf("expected ErrorCode AUTH_EXPIRED, got %s", res.ErrorCode)
	}
	if res.HttpStatus != 401 {
		t.Errorf("expected HttpStatus 401, got %d", res.HttpStatus)
	}
}

func TestRetrieveItemDetailsInFoldersHTTP404(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte(`{"errorReason": "Not Found"}`))
	}))
	defer ts.Close()

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	session.DrivewsURL = ts.URL

	resJSON := RetrieveItemDetailsInFolders("FOLDER::com.apple.CloudDocs::nonexistent")
	var res DriveFolderListResult
	if err := json.Unmarshal([]byte(resJSON), &res); err != nil {
		t.Fatalf("failed to unmarshal result: %v", err)
	}

	if res.Success {
		t.Fatalf("expected failure on 404, got success")
	}
	if res.ErrorCode != ErrCodeNotFound {
		t.Errorf("expected ErrorCode NOT_FOUND, got %s", res.ErrorCode)
	}
	if res.HttpStatus != 404 {
		t.Errorf("expected HttpStatus 404, got %d", res.HttpStatus)
	}
}

func TestSessionDrivewsURLPopulation(t *testing.T) {
	validateJSON := []byte(`{
		"dsInfo": {"dsid": "12345"},
		"webservices": {
			"ckdatabasews": {"url": "https://p203-ckdatabasews.icloud.com.cn:443", "status": "active"},
			"drivews": {"url": "https://p203-drivews.icloud.com.cn:443", "status": "active"}
		}
	}`)

	session, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}

	initRes, err := session.handleValidateResponse(validateJSON, true)
	if err != nil {
		t.Fatalf("handleValidateResponse failed: %v", err)
	}

	if initRes.DrivewsURL != "https://p203-drivews.icloud.com.cn" {
		t.Errorf("expected normalized drivewsUrl 'https://p203-drivews.icloud.com.cn', got %s", initRes.DrivewsURL)
	}
	if session.DrivewsURL != "https://p203-drivews.icloud.com.cn" {
		t.Errorf("expected session.DrivewsURL 'https://p203-drivews.icloud.com.cn', got %s", session.DrivewsURL)
	}
}
