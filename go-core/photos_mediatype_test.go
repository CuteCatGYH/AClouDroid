package icloudcore

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Media type inference decides how the Android side treats an asset: a `VIDEO` is downloaded as
// itself and played, while a `LIVE_PHOTO` gets its paired clip packed into a JPEG-shaped container
// by `MediaStoreDownloader.downloadLivePhotoMotionPhoto`.
//
// That makes the precedence between the two signals load-bearing. The order used to be
//
//	if videoComplUrl != ""      -> LIVE_PHOTO
//	else if looks like a movie  -> VIDEO
//
// so a movie that carried a `resVidComplRes` reported itself as a Live Photo, and the downloader
// would have appended the actual movie into a Motion Photo - producing a file that is neither a
// valid JPEG nor a playable video. These tests pin the corrected order: the item type decides
// video-vs-photo, and the paired resource only separates a Live Photo from a still.

// servePage returns the parsed first photo from a canned `/records/query` response.
func servePage(t *testing.T, assetFields, masterFields string) PhotoItem {
	t.Helper()

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.URL.Path, "records/query") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"records": [
				{
					"recordName": "asset-1",
					"recordType": "CPLAsset",
					"fields": ` + assetFields + `
				},
				{
					"recordName": "master-1",
					"recordType": "CPLMaster",
					"fields": ` + masterFields + `
				}
			],
			"continuationMarker": "",
			"syncToken": "t"
		}`))
	}))
	defer ts.Close()

	session := newTestSession(t)
	session.CKDatabaseURL = ts.URL
	session.DSID = "12345678"
	installSessionForTest(t, session)

	var page PhotoPageResult
	if err := json.Unmarshal([]byte(FetchPhotoPage("", 50)), &page); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !page.Success {
		t.Fatalf("page failed: %s", page.ErrorMessage)
	}
	if len(page.Photos) != 1 {
		t.Fatalf("expected 1 photo, got %d", len(page.Photos))
	}
	return page.Photos[0]
}

func TestMediaTypeMovieWithComplResIsVideo(t *testing.T) {
	asset := `{
		"masterRef": {"value": {"recordName": "master-1"}, "type": "REFERENCE"},
		"assetDate": {"value": 1690000000000, "type": "TIMESTAMP"},
		"duration":  {"value": 7707, "type": "INT64"}
	}`
	master := `{
		"itemType": {"value": "public.mpeg-4", "type": "STRING"},
		"filenameEnc": {"value": "SU1HXzAwMDEuTVBW", "type": "ENCRYPTED_BYTES"},
		"resOriginalRes": {"value": {"downloadURL": "https://cvws.example.com/original.mp4"}, "type": "ASSETID"},
		"resOriginalVidComplRes": {"value": {"downloadURL": "https://cvws.example.com/compl.mp4"}, "type": "ASSETID"}
	}`

	got := servePage(t, asset, master)
	if got.MediaType != "VIDEO" {
		t.Fatalf("movie with a paired resource reported MediaType=%q, want VIDEO; "+
			"LIVE_PHOTO would make the downloader pack it into a JPEG container", got.MediaType)
	}
	// The paired URL is still surfaced - it is legitimate data, just not a type signal.
	if got.VideoComplURL != "https://cvws.example.com/compl.mp4" {
		t.Errorf("VideoComplURL = %q, want it preserved", got.VideoComplURL)
	}
}

func TestMediaTypeLivePhotoStill(t *testing.T) {
	asset := `{
		"masterRef": {"value": {"recordName": "master-1"}, "type": "REFERENCE"},
		"assetDate": {"value": 1690000000000, "type": "TIMESTAMP"}
	}`
	master := `{
		"itemType": {"value": "public.heic", "type": "STRING"},
		"filenameEnc": {"value": "SU1HXzAwMDEuSEVJQw==", "type": "ENCRYPTED_BYTES"},
		"resOriginalRes": {"value": {"downloadURL": "https://cvws.example.com/original.heic"}, "type": "ASSETID"},
		"resOriginalVidComplRes": {"value": {"downloadURL": "https://cvws.example.com/compl.mov"}, "type": "ASSETID"}
	}`

	got := servePage(t, asset, master)
	if got.MediaType != "LIVE_PHOTO" {
		t.Fatalf("still image with a paired video reported MediaType=%q, want LIVE_PHOTO", got.MediaType)
	}
}

// TestMediaTypeVideoByDurationOnly covers the fallback path: no recognisable item type, but the
// asset carries a non-zero duration, which only a movie does.
func TestMediaTypeVideoByDurationOnly(t *testing.T) {
	asset := `{
		"masterRef": {"value": {"recordName": "master-1"}, "type": "REFERENCE"},
		"assetDate": {"value": 1690000000000, "type": "TIMESTAMP"},
		"duration":  {"value": 3000, "type": "INT64"}
	}`
	master := `{
		"itemType": {"value": "application/octet-stream", "type": "STRING"},
		"filenameEnc": {"value": "Y2xpcC5iaW4=", "type": "ENCRYPTED_BYTES"},
		"resOriginalRes": {"value": {"downloadURL": "https://cvws.example.com/original.bin"}, "type": "ASSETID"}
	}`

	got := servePage(t, asset, master)
	if got.MediaType != "VIDEO" {
		t.Fatalf("MediaType = %q, want VIDEO (duration > 0)", got.MediaType)
	}
}

// TestMediaTypeQuickTimeMovie guards the `.mov` UTI an iPhone produces. It contains neither
// "video" nor "mp4", so an item-type substring check alone would miss it.
func TestMediaTypeQuickTimeMovie(t *testing.T) {
	asset := `{
		"masterRef": {"value": {"recordName": "master-1"}, "type": "REFERENCE"},
		"assetDate": {"value": 1690000000000, "type": "TIMESTAMP"}
	}`
	master := `{
		"itemType": {"value": "com.apple.quicktime-movie", "type": "STRING"},
		"filenameEnc": {"value": "SU1HXzAwMDEuTU9W", "type": "ENCRYPTED_BYTES"},
		"resOriginalRes": {"value": {"downloadURL": "https://cvws.example.com/original.mov"}, "type": "ASSETID"}
	}`

	got := servePage(t, asset, master)
	if got.MediaType != "VIDEO" {
		t.Fatalf("MediaType = %q, want VIDEO for com.apple.quicktime-movie", got.MediaType)
	}
}
