package icloudcore

import (
	"encoding/json"
	"os"
	"testing"
)

func TestUploadPhotoAssetGuards(t *testing.T) {
	// Test case 1: Nonexistent file path
	resNoFileStr := UploadPhotoAssetByPath("/nonexistent/path.jpg", "test.jpg", "image/jpeg", "")
	var res1 UploadResult
	json.Unmarshal([]byte(resNoFileStr), &res1)
	if res1.Success {
		t.Fatalf("Expected failure when file does not exist, got success")
	}
	if res1.ErrorCode != "FILE_OPEN_ERROR" {
		t.Fatalf("Expected FILE_OPEN_ERROR error code, got %s", res1.ErrorCode)
	}

	// Test case 2: Invalid FD
	resInvalidFdStr := UploadPhotoAsset(-1, "test.jpg", "image/jpeg", "")
	var res2 UploadResult
	json.Unmarshal([]byte(resInvalidFdStr), &res2)
	if res2.Success {
		t.Fatalf("Expected failure when invalid FD, got success")
	}

	// Test case 3: Active session without PCS-Photos cookie.
	//
	// The session must be INSTALLED, not merely built: `UploadPhotoAssetByPath` reads the
	// process-global session, so constructing one and discarding the result (which is what this
	// test used to do) left it asserting against `NO_SESSION` instead of the guard it targets.
	session, err := NewSession("X-APPLE-WEBAUTH-TOKEN=token; X-APPLE-WEBAUTH-PCS-CloudKit=ck", true)
	if err != nil {
		t.Fatalf("Failed to create mock session: %v", err)
	}
	installSessionForTest(t, session)

	// Create temp file
	tmp, err := os.CreateTemp("", "test_upload_*.jpg")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()

	tmp.Write([]byte("mock content"))

	resNoPcsStr := UploadPhotoAssetByPath(tmp.Name(), "test.jpg", "image/jpeg", "")
	var res3 UploadResult
	json.Unmarshal([]byte(resNoPcsStr), &res3)
	if res3.Success {
		t.Fatalf("Expected failure when PCS-Photos missing, got success")
	}
	if res3.ErrorCode != "MISSING_PCS_PHOTOS" {
		t.Fatalf("Expected MISSING_PCS_PHOTOS error code, got %s", res3.ErrorCode)
	}

	t.Logf("SUCCESS! Upload guards verified correctly.")
}
