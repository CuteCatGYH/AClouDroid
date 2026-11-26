package icloudcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestPhotosRegionLimited(t *testing.T) {
	cases := map[string]bool{
		"https://p203-ckdatabasews.icloud.com.cn:443": true,
		"https://p203-ckdatabasews.icloud.com.cn":     true,
		"https://p68-ckdatabasews.icloud.com:443":     false,
		"https://p68-ckdatabasews.icloud.com/ws/cn":   false,
		"":                                            false,
	}
	for u, want := range cases {
		s := newTestSession(t)
		s.CKDatabaseURL = u
		if got := photosRegionLimited(s); got != want {
			t.Errorf("%q: got %v want %v", u, got, want)
		}
	}
}

// Over 1 GiB on 云上贵州 without a native login must fail at once with REGION_UPLOAD_LIMIT. The
// old path streamed ~1 GiB and then collected a 413, three times per file. The session's
// cookies are fake, so any request that did go out would fail with a network/auth code instead.
func TestOverLimitWithoutNativeLoginFailsBeforeTraffic(t *testing.T) {
	s, err := NewSession("X-APPLE-WEBAUTH-TOKEN=t; X-APPLE-WEBAUTH-PCS-CloudKit=c; X-APPLE-WEBAUTH-PCS-Photos=p", true)
	if err != nil {
		t.Fatal(err)
	}
	s.CKDatabaseURL = "https://p203-ckdatabasews.icloud.com.cn:443"
	installSessionForTest(t, s)
	NativeLogout()
	t.Cleanup(NativeLogout)

	// Sparse: 1 GiB + 1 byte costs no disk.
	path := filepath.Join(t.TempDir(), "big.mp4")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.Truncate(MainlandChinaSingleFileLimitBytes + 1); err != nil {
		t.Fatal(err)
	}
	f.Close()

	var res UploadResult
	json.Unmarshal([]byte(UploadPhotoAssetByPath(path, "big.mp4", "video/mp4", "")), &res)
	if res.Success || res.ErrorCode != ErrCodeRegionUploadLimit {
		t.Fatalf("want %s, got %+v", ErrCodeRegionUploadLimit, res)
	}
}

func TestNativeConfigureRoundTrip(t *testing.T) {
	t.Cleanup(NativeLogout)
	var st NativeStatusResult
	json.Unmarshal([]byte(NativeConfigure("", "http://127.0.0.1:6969/")), &st)
	if !st.Success || st.Configured {
		t.Fatalf("empty creds should be unconfigured: %+v", st)
	}
	json.Unmarshal([]byte(NativeConfigure("{not json", "http://127.0.0.1:6969/")), &st)
	if st.Success {
		t.Fatal("garbage credentials accepted")
	}
	creds := `{"appleId":"a@b.c","dsid":"1","mmeAuthToken":"t","deviceUuid":"D","hardwareId":"H","boundarySecret":"c2VjcmV0c2VjcmV0c2VjcmV0"}`
	json.Unmarshal([]byte(NativeConfigure(creds, "http://127.0.0.1:6969/")), &st)
	if !st.Success || !st.Configured || st.AppleID != "a@b.c" {
		t.Fatalf("configure failed: %+v", st)
	}
	NativeLogout()
	json.Unmarshal([]byte(NativeStatus()), &st)
	if st.Configured {
		t.Fatal("logout did not clear credentials")
	}
}
