package icloudcore

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"icloudcore/native"
)

// UploadMetadata contains EXIF and file attributes extracted from Android side
type UploadMetadata struct {
	AssetDate            int64    `json:"assetDate"`            // Epoch millis from EXIF DateTimeOriginal
	OriginalCreationDate int64    `json:"originalCreationDate"` // Epoch millis
	Width                int      `json:"width"`
	Height               int      `json:"height"`
	Orientation          int      `json:"orientation"`
	ItemType             string   `json:"itemType"` // e.g. "public.jpeg", "public.heic", "public.mpeg-4"
	FileType             string   `json:"fileType"` // e.g. "image/jpeg", "image/heic", "video/mp4"
	Lat                  *float64 `json:"lat,omitempty"`
	Lon                  *float64 `json:"lon,omitempty"`

	// Detailed Camera EXIF
	Make             string   `json:"make,omitempty"`
	Model            string   `json:"model,omitempty"`
	Software         string   `json:"software,omitempty"`
	LensModel        string   `json:"lensModel,omitempty"`
	FNumber          *float64 `json:"fNumber,omitempty"`
	ExposureTime     *float64 `json:"exposureTime,omitempty"`
	Iso              *int     `json:"iso,omitempty"`
	FocalLength      *float64 `json:"focalLength,omitempty"`
	FocalLength35    *int     `json:"focalLength35,omitempty"`
	Altitude         *float64 `json:"altitude,omitempty"`
	DateTimeOriginal string   `json:"dateTimeOriginal,omitempty"`

	// Video attributes. Apple stores these on `CPLMaster` for every video record; without
	// `videoFrameRate` / `codec` the client has nothing to describe the stream with.
	Codec          string   `json:"codec,omitempty"`
	VideoFrameRate *float64 `json:"videoFrameRate,omitempty"`
	DurationMs     int64    `json:"durationMs,omitempty"`

	// QuickTime metadata injected into the movie header we ship as `moop`.
	//
	// This is what the Apple clients render in the video info panel. Without it the asset plays
	// and shows a duration but reports "no camera information". See quicktime_meta.go.
	VideoMake        string `json:"videoMake,omitempty"`
	VideoModel       string `json:"videoModel,omitempty"`
	VideoSoftware    string `json:"videoSoftware,omitempty"`
	LocationAccuracy string `json:"locationAccuracy,omitempty"`

	// Derivatives & EXIF
	ThumbPath        string `json:"thumbPath,omitempty"` // Local temp file path for resJPEGThumbRes (320px JPEG)
	ThumbWidth       int    `json:"thumbWidth,omitempty"`
	ThumbHeight      int    `json:"thumbHeight,omitempty"`
	MedPath          string `json:"medPath,omitempty"` // Local temp file path for resJPEGMedRes (2048px JPEG)
	MedWidth         int    `json:"medWidth,omitempty"`
	MedHeight        int    `json:"medHeight,omitempty"`
	MediaMetaDataEnc string `json:"mediaMetaDataEnc,omitempty"` // Base64 encoded binary/xml plist
	LocationEnc      string `json:"locationEnc,omitempty"`      // Base64 encoded location

	// Live Photo (Motion Photo) companion attributes
	IsLivePhoto        bool   `json:"isLivePhoto,omitempty"`
	LivePhotoVideoPath string `json:"livePhotoVideoPath,omitempty"` // Local temp file path for companion QuickTime video
	MediaGroupId       string `json:"mediaGroupId,omitempty"`       // UUID pairing the still image and video companion
	VidComplDurValue   int64  `json:"vidComplDurValue,omitempty"`   // Duration in ns (e.g. 2631666667)
	VidComplDurScale   int64  `json:"vidComplDurScale,omitempty"`   // Duration scale (1000000000)
	VidComplDispValue  int64  `json:"vidComplDispValue,omitempty"`  // Display timestamp in ns (e.g. 1349973637)
	VidComplDispScale  int64  `json:"vidComplDispScale,omitempty"`  // Display timestamp scale (1000000000)
	VidComplWidth      int    `json:"vidComplWidth,omitempty"`      // Companion video width
	VidComplHeight     int    `json:"vidComplHeight,omitempty"`     // Companion video height
}

type UploadResult struct {
	Success      bool   `json:"success"`
	MasterID     string `json:"masterId,omitempty"`
	AssetID      string `json:"assetId,omitempty"`
	FileChecksum string `json:"fileChecksum,omitempty"`
	// LocalSha256 is the SHA-256 we computed to sign `/assets/upload`. Returned so the caller can
	// persist it into `upload_queue.localSha256`; without it the local dedupe layer never had
	// anything to match against and always fell through to the network path.
	LocalSha256 string `json:"localSha256,omitempty"`
	// Duplicate is true when the file was already present as a `CPLMaster` and no record was
	// created. Callers must treat it as a success, not a failure.
	Duplicate bool `json:"duplicate,omitempty"`
	// Warnings lists optional fields that were skipped, and why.
	//
	// Optional fields are the ones whose absence degrades the asset without failing the upload
	// (`mediaMetaDataEnc`, `codec`, ...). Swallowing their errors silently is what made ISS-040
	// take two rounds to diagnose: the record looked created, but half its metadata was missing
	// and nothing anywhere said so.
	Warnings  []string `json:"warnings,omitempty"`
	ErrorCode string   `json:"errorCode,omitempty"`
	ErrorMsg    string `json:"errorMsg,omitempty"`
}

func makeUploadError(code string, msg string) string {
	res := UploadResult{
		Success:   false,
		ErrorCode: code,
		ErrorMsg:  msg,
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// masterRecordExists reports whether a `CPLMaster` with this record name is already stored.
//
// `CPLMaster.recordName` is the MMCS `fileChecksum`, i.e. a content fingerprint, so this is an
// exact "have we uploaded these bytes before" test - no local hashing or mapping table needed.
//
// Failures return false on purpose: the only consequence of a false negative is that we try to
// create the record, and CloudKit then rejects the duplicate by name (which
// [extractRecordErrors] now surfaces). Treating an inconclusive lookup as "exists" would
// silently drop genuinely new uploads.
func masterRecordExists(s *Session, masterID string) bool {
	if s == nil || masterID == "" {
		return false
	}

	payload := map[string]interface{}{
		"records": []map[string]string{{"recordName": masterID}},
		"zoneID":  map[string]interface{}{"zoneName": "PrimarySync"},
		// `desiredKeys` is a whitelist; asking only for `recordName` keeps the round trip tiny.
		"desiredKeys": []string{"recordName"},
	}

	reqBody, _ := json.Marshal(payload)
	req, err := s.NewRequest("POST", s.photosBaseURL("records/lookup"), string(reqBody))
	if err != nil {
		return false
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	s.ExtractAndSaveCookies(resp)

	if resp.StatusCode != http.StatusOK {
		return false
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false
	}

	var parsed struct {
		Records []struct {
			RecordName      string `json:"recordName"`
			RecordType      string `json:"recordType"`
			ServerErrorCode string `json:"serverErrorCode"`
			Deleted         bool   `json:"deleted"`
		} `json:"records"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return false
	}

	for _, rec := range parsed.Records {
		if rec.Deleted || rec.ServerErrorCode != "" {
			continue
		}
		if rec.RecordType == "CPLMaster" {
			return true
		}
	}
	return false
}

// extractRecordErrors pulls per-record failures out of a `/records/modify` response body.
//
// CloudKit signals per-record problems with `serverErrorCode` / `reason` on each entry of
// `records`, and may still answer `200 OK` overall. Returns "" when the body carries no record
// errors, or when it cannot be parsed as a CloudKit response at all (a parse failure must never
// be reported as an upload failure — see AGENTS §7.1 on not guessing from raw text).
func extractRecordErrors(body string) string {
	if strings.TrimSpace(body) == "" {
		return ""
	}

	var resp struct {
		Records []struct {
			RecordName      string `json:"recordName"`
			RecordType      string `json:"recordType"`
			ServerErrorCode string `json:"serverErrorCode"`
			Reason          string `json:"reason"`
		} `json:"records"`
	}
	if err := json.Unmarshal([]byte(body), &resp); err != nil {
		return ""
	}

	var parts []string
	for _, rec := range resp.Records {
		if rec.ServerErrorCode == "" {
			continue
		}
		desc := fmt.Sprintf("%s(%s): %s", rec.RecordType, rec.RecordName, rec.ServerErrorCode)
		if rec.Reason != "" {
			desc += " - " + rec.Reason
		}
		parts = append(parts, desc)
	}

	return strings.Join(parts, "; ")
}

// UploadPhotoAsset performs zero-memory streaming upload using an open file descriptor (fd).
func UploadPhotoAsset(fd int, filename string, mimeType string, metadataJson string) string {
	f := os.NewFile(uintptr(fd), filename)
	if f == nil {
		return makeUploadError("INVALID_FD", "Failed to create os.File from file descriptor")
	}
	defer f.Close()

	return uploadPhotoAssetFromFile(f, filename, mimeType, metadataJson)
}

// UploadPhotoAssetByPath performs zero-memory streaming upload using a file path.
func UploadPhotoAssetByPath(filePath string, filename string, mimeType string, metadataJson string) string {
	f, err := os.Open(filePath)
	if err != nil {
		return makeUploadError("FILE_OPEN_ERROR", err.Error())
	}
	defer f.Close()

	return uploadPhotoAssetFromFile(f, filename, mimeType, metadataJson)
}

type mmcsReceipt struct {
	ReferenceChecksum string `json:"referenceChecksum"`
	Size              int64  `json:"size"`
	FileChecksum      string `json:"fileChecksum"`
	WrappingKey       string `json:"wrappingKey"`
	Receipt           string `json:"receipt"`
}

func computeFileSha256Base64(f *os.File) (string, error) {
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	h256 := sha256.New()
	if _, err := io.Copy(h256, f); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(h256.Sum(nil)), nil
}

func uploadFileToUrl(f *os.File, fileSize int64, uploadUrl string) (*mmcsReceipt, error) {
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}

	streamReq, err := http.NewRequest("POST", uploadUrl, f)
	if err != nil {
		return nil, err
	}
	streamReq.ContentLength = fileSize
	streamReq.Header.Set("Content-Type", "application/octet-stream")

	streamClient := &http.Client{Timeout: 0}
	streamResp, err := streamClient.Do(streamReq)
	if err != nil {
		return nil, err
	}
	defer streamResp.Body.Close()

	if streamResp.StatusCode != 200 {
		buf := new(bytes.Buffer)
		buf.ReadFrom(streamResp.Body)
		return nil, fmt.Errorf("status %d: %s", streamResp.StatusCode, buf.String())
	}

	receiptBytes, err := io.ReadAll(streamResp.Body)
	if err != nil {
		return nil, err
	}

	var receiptObj struct {
		SingleFile mmcsReceipt `json:"singleFile"`
	}
	if err := json.Unmarshal(receiptBytes, &receiptObj); err != nil {
		return nil, err
	}
	if receiptObj.SingleFile.FileChecksum == "" {
		return nil, fmt.Errorf("empty fileChecksum in receipt")
	}

	return &receiptObj.SingleFile, nil
}

func uploadPhotoAssetFromFile(f *os.File, filename string, mimeType string, metadataJson string) string {
	s := GetSession()
	if s == nil {
		return makeUploadError("NO_SESSION", "No active session in Go core")
	}

	// P0 Guard: Verify PCS-Photos cookie
	if !s.HasCookieIgnoreCase("X-APPLE-WEBAUTH-PCS-Photos") {
		return makeUploadError("MISSING_PCS_PHOTOS", "X-APPLE-WEBAUTH-PCS-Photos cookie is missing. Re-authentication required.")
	}

	// 1. Stat file size
	info, err := f.Stat()
	if err != nil {
		return makeUploadError("FILE_STAT_ERROR", err.Error())
	}
	fileSize := info.Size()

	// Region gate (ISS-057): on 云上贵州 the web gateway rejects bodies over 1 GiB with 413, so
	// such files go through native MMCS instead. Decided before any traffic: without a native
	// login the upload fails here, immediately and with a reason, instead of streaming ~1 GiB
	// first and collecting a 413 on every retry.
	useNative := fileSize > MainlandChinaSingleFileLimitBytes && photosRegionLimited(s)
	var nativeUp *native.PhotosUploader
	var nativePrep *native.Prepared
	if useNative {
		creds, ani := nativeSnapshot()
		if creds == nil {
			return makeUploadError(ErrCodeRegionUploadLimit,
				"文件超过 1 GiB，云上贵州账号需在设置里登录“大文件上传”后才能上传")
		}
		s.mu.RLock()
		webDSID := s.DSID
		s.mu.RUnlock()
		if webDSID != "" && creds.DSID != webDSID {
			return makeUploadError(ErrCodeNativeAccountMismatch,
				"“大文件上传”登录的 Apple ID 与当前账号不同，请在设置里重新登录")
		}
		up, err := native.NewPhotosUploader(ani, creds)
		if err != nil {
			return makeUploadError(classifyNativeErr(err), err.Error())
		}
		prep, err := up.Prepare(f, fileSize)
		if err != nil {
			return makeUploadError("FILE_HASH_ERROR", err.Error())
		}
		nativeUp, nativePrep = up, prep
		// The native fileChecksum is known before upload, so duplicates cost no traffic
		// (AGENTS Phase 3 / ISS-068: idempotency must come before the bytes).
		if masterRecordExists(s, prep.FileChecksum()) {
			b, _ := json.Marshal(UploadResult{Success: true, MasterID: prep.FileChecksum(), FileChecksum: prep.FileChecksum(), Duplicate: true})
			return string(b)
		}
	}

	// Parse metadata
	var meta UploadMetadata
	if metadataJson != "" {
		_ = json.Unmarshal([]byte(metadataJson), &meta)
	}

	// Default fallback values if metadata fields are missing
	nowMs := time.Now().UnixMilli()
	if meta.AssetDate <= 0 {
		meta.AssetDate = nowMs
	}
	if meta.OriginalCreationDate <= 0 {
		meta.OriginalCreationDate = meta.AssetDate
	}
	if meta.ItemType == "" {
		if strings.HasPrefix(mimeType, "video/") {
			meta.ItemType = "public.mpeg-4"
		} else if mimeType == "image/png" {
			meta.ItemType = "public.png"
		} else if mimeType == "image/gif" {
			meta.ItemType = "com.compuserve.gif"
		} else if mimeType == "image/heic" || mimeType == "image/heif" {
			meta.ItemType = "public.heic"
		} else {
			meta.ItemType = "public.jpeg"
		}
	}
	// `FileType` is deliberately NOT taken from the caller. Apple stores a **UTI** here
	// (`public.heic`, `public.jpeg`, `public.mpeg-4`), never a MIME type. Writing `image/heic`
	// made every asset undisplayable in iCloud web / macOS / iOS while our own client was fine,
	// because our reader never looks at this field at all (ISS-040).
	//
	// `ItemType` is already the UTI, so it is the single source of truth for both fields.
	meta.FileType = meta.ItemType
	if meta.Orientation <= 0 {
		meta.Orientation = 1
	}

	isVideo := strings.HasPrefix(meta.ItemType, "public.mpeg") ||
		strings.HasPrefix(meta.ItemType, "public.movie") ||
		strings.HasPrefix(meta.ItemType, "public.quicktime") ||
		strings.HasPrefix(meta.ItemType, "com.apple.quicktime")

	// A real UUID, not `ASSET-<nanos>`. Apple's own records use lowercase UUIDs, and the asset
	// record name shows up in the web URL (`/photos/.../l,pz,<assetId>,<n>/`), so an
	// obviously non-conforming id is both a tell and a risk.
	assetUuid := newUUIDv4()

	// 2. Compute SHA-256 for original file and derivative files (if present)
	sha256Base64, err := computeFileSha256Base64(f)
	if err != nil {
		return makeUploadError("FILE_HASH_ERROR", err.Error())
	}

	// Extract the movie header **now**, before the streaming uploads below.
	//
	// `uploadFileToUrl` hands this `*os.File` to `http.NewRequest` as the request body. An
	// `*os.File` satisfies `io.ReadCloser`, so net/http takes ownership and **closes it** once
	// the POST completes. By the time the record is being assembled, every later `ReadAt` fails
	// with "file already closed" and `extractMovieProxy` silently returned nothing - which is
	// why every video we uploaded shipped without `mediaMetaDataEnc` (no duration, no track
	// layout) while the same code worked fine in unit tests, where no upload happens first.
	var videoMetaEnc string
	var videoMetaOK bool
	var detectedCodec string
	if isVideo {
		qt := QuickTimeMeta{
			Make:             meta.VideoMake,
			Model:            meta.VideoModel,
			Software:         meta.VideoSoftware,
			CreationDate:     formatQuickTimeDate(meta.OriginalCreationDate),
			LocationISO6709:  formatISO6709(optionalFloat(meta.Lat), optionalFloat(meta.Lon)),
			LocationAccuracy: meta.LocationAccuracy,
		}
		// When the caller could not supply a location, [buildVideoMetaDataEnc] falls back to the
		// container's own `udta/©xyz` atom. That does not bypass ACCESS_MEDIA_LOCATION - with the
		// permission denied, MediaProvider removes the atom from the stream (measured).
		videoMetaEnc, detectedCodec, videoMetaOK =
			buildVideoMetaDataEnc(f, fileSize, assetUuid, filename, qt)
	}

	var fThumb *os.File
	var thumbSize int64
	var thumbSha256 string
	if meta.ThumbPath != "" {
		if ft, err := os.Open(meta.ThumbPath); err == nil {
			if infoT, err := ft.Stat(); err == nil && infoT.Size() > 0 {
				if shaT, err := computeFileSha256Base64(ft); err == nil {
					fThumb = ft
					thumbSize = infoT.Size()
					thumbSha256 = shaT
					defer fThumb.Close()
				}
			}
		}
	}

	var fMed *os.File
	var medSize int64
	var medSha256 string
	if meta.MedPath != "" {
		if fm, err := os.Open(meta.MedPath); err == nil {
			if infoM, err := fm.Stat(); err == nil && infoM.Size() > 0 {
				if shaM, err := computeFileSha256Base64(fm); err == nil {
					fMed = fm
					medSize = infoM.Size()
					medSha256 = shaM
					defer fMed.Close()
				}
			}
		}
	}

	var fVid *os.File
	var vidSize int64
	var vidSha256 string
	if meta.IsLivePhoto && meta.LivePhotoVideoPath != "" {
		if fv, err := os.Open(meta.LivePhotoVideoPath); err == nil {
			if infoV, err := fv.Stat(); err == nil && infoV.Size() > 0 {
				if shaV, err := computeFileSha256Base64(fv); err == nil {
					fVid = fv
					vidSize = infoV.Size()
					vidSha256 = shaV
					defer fVid.Close()
				}
			}
		}
	}

	// 3. Request /assets/upload token for original + derivatives. A native upload sends the
	// original itself; the small derivatives still go through the web path.
	tokensReq := []map[string]interface{}{}
	if !useNative {
		tokensReq = append(tokensReq, map[string]interface{}{
			"recordType": "CPLMaster",
			"fieldName":  "resOriginalRes",
			"signature":  sha256Base64,
		})
	}
	if vidSha256 != "" {
		tokensReq = append(tokensReq, map[string]interface{}{
			"recordType": "CPLMaster",
			"fieldName":  "resOriginalVidComplRes",
			"signature":  vidSha256,
		})
	}
	if thumbSha256 != "" {
		tokensReq = append(tokensReq, map[string]interface{}{
			"recordType": "CPLMaster",
			"fieldName":  "resJPEGThumbRes",
			"signature":  thumbSha256,
		})
	}
	if medSha256 != "" {
		tokensReq = append(tokensReq, map[string]interface{}{
			"recordType": "CPLMaster",
			"fieldName":  "resJPEGMedRes",
			"signature":  medSha256,
		})
	}

	// 4a. Native original first: a multi-GiB transfer can outlast the web upload URLs, so those
	// are only requested once the big bytes are in.
	var origReceipt *mmcsReceipt
	if useNative {
		rc, err := nativeUp.Upload(nativePrep, f, nil)
		if err != nil {
			code := ErrCodeNativeUploadFailed
			if c := classifyNativeErr(err); c == ErrCodeNativeAuthExpired || c == ErrCodeTimeout {
				code = c
			}
			return makeUploadError(code, fmt.Sprintf("resOriginalRes native upload failed: %v", err))
		}
		origReceipt = &mmcsReceipt{
			FileChecksum:      rc.FileChecksum,
			ReferenceChecksum: rc.ReferenceChecksum,
			WrappingKey:       rc.WrappingKey,
			Receipt:           rc.Receipt,
			Size:              rc.Size,
		}
	}

	uploadUrlMap := map[string]string{}
	if len(tokensReq) > 0 {
		m, errJSON := requestAssetUploadURLs(s, tokensReq)
		if errJSON != "" {
			return errJSON
		}
		uploadUrlMap = m
	}

	if !useNative {
		origUploadUrl := uploadUrlMap["resOriginalRes"]
		if origUploadUrl == "" {
			return makeUploadError("UNSUPPORTED_UPLOAD_TOKEN", "No singleFileUpload url in token for resOriginalRes")
		}

		// 4. Stream upload files to respective MMCS upload URLs
		rec, err := uploadFileToUrl(f, fileSize, origUploadUrl)
		if err != nil {
			return makeUploadError("STREAM_UPLOAD_FAILED", fmt.Sprintf("resOriginalRes upload failed: %v", err))
		}
		origReceipt = rec
	}

	var thumbReceipt *mmcsReceipt
	if fThumb != nil {
		if thumbUrl := uploadUrlMap["resJPEGThumbRes"]; thumbUrl != "" {
			if rec, err := uploadFileToUrl(fThumb, thumbSize, thumbUrl); err == nil {
				thumbReceipt = rec
			}
		}
	}

	var medReceipt *mmcsReceipt
	if fMed != nil {
		if medUrl := uploadUrlMap["resJPEGMedRes"]; medUrl != "" {
			if rec, err := uploadFileToUrl(fMed, medSize, medUrl); err == nil {
				medReceipt = rec
			}
		}
	}

	var uploadWarnings []string

	var vidReceipt *mmcsReceipt
	if fVid != nil {
		if vidUrl := uploadUrlMap["resOriginalVidComplRes"]; vidUrl != "" {
			if rec, err := uploadFileToUrl(fVid, vidSize, vidUrl); err == nil {
				vidReceipt = rec
			} else {
				uploadWarnings = append(uploadWarnings, fmt.Sprintf("resOriginalVidComplRes upload failed: %v", err))
			}
		} else {
			uploadWarnings = append(uploadWarnings, "resOriginalVidComplRes token/url was missing from /assets/upload response")
		}
	}

	mmcsChecksum := origReceipt.FileChecksum
	if mmcsChecksum == "" {
		return makeUploadError("EMPTY_FILE_CHECKSUM", "fileChecksum in receipt is empty")
	}

	// 5. Idempotency guard: has this exact file already become a `CPLMaster`?
	//
	// `CPLMaster.recordName` **is** the MMCS `fileChecksum`, so a single lookup answers it.
	//
	// Without this, uploading the same file twice created a second `CPLAsset` pointing at the
	// same master. Apple's clients collapse that (they key on the master), so the library looked
	// clean there - while our own grid keys on the asset record name and therefore showed the
	// photo twice. That one-sided duplication is what made local de-duplication look broken.
	//
	// Skipping the create is always safe: an existing master means the bytes and the record are
	// already in the library.
	if masterRecordExists(s, mmcsChecksum) {
		res := UploadResult{
			Success:      true,
			MasterID:     mmcsChecksum,
			FileChecksum: mmcsChecksum,
			LocalSha256:  sha256Base64,
			Duplicate:    true,
		}
		b, _ := json.Marshal(res)
		return string(b)
	}

	// 6. Construct Atomic /records/modify Request Body
	filenameEnc := base64.StdEncoding.EncodeToString([]byte(filename))

	// Accumulates the result while the record is being built, so optional-field skips can be
	// recorded as they happen instead of being swallowed.
	res := UploadResult{Success: true, FileChecksum: mmcsChecksum, Warnings: uploadWarnings}

	fileResourceObj := map[string]interface{}{
		"size":              origReceipt.Size,
		"fileChecksum":      origReceipt.FileChecksum,
		"referenceChecksum": origReceipt.ReferenceChecksum,
		"wrappingKey":       origReceipt.WrappingKey,
		"receipt":           origReceipt.Receipt,
	}

	masterFields := map[string]interface{}{
		"itemType": map[string]interface{}{
			"value": meta.ItemType,
		},
		"resOriginalRes": map[string]interface{}{
			"value": fileResourceObj,
		},
		"resOriginalFingerprint": map[string]interface{}{
			"value": mmcsChecksum,
		},
		"filenameEnc": map[string]interface{}{
			"value": filenameEnc,
		},
		"resOriginalFileType": map[string]interface{}{
			"value": meta.FileType,
		},
		"resOriginalFileSize": map[string]interface{}{
			"value": fileSize,
		},
		"originalOrientation": map[string]interface{}{
			"value": meta.Orientation,
		},
		"originalCreationDate": map[string]interface{}{
			"value": meta.OriginalCreationDate,
		},
		"importDate": map[string]interface{}{
			"value": nowMs,
		},

		// Fields present on **every** record Apple creates and absent from ours (ISS-040).
		// `importedBy` / `fullSizeJPEGSource` disagree between Apple's web uploader (10 / 1)
		// and on-device captures (0 / 0). We follow the **web uploader** convention, because
		// that is what we are: an importer, not a camera.
		"importedBy": map[string]interface{}{
			"value": 10,
		},
		"fullSizeJPEGSource": map[string]interface{}{
			"value": boolToInt(!isVideo),
		},
		"importGroupId": map[string]interface{}{
			"value": newUUIDv4(),
		},
	}

	if isVideo {
		// The container wins over the caller's hint. `MediaMetadataRetriever` reports a MIME type
		// (`video/mp4`), which names the container rather than the codec, so a hint derived from
		// it is usually useless; `stsd` is authoritative. The caller's value is only used when
		// the file itself could not be parsed.
		codec := detectedCodec
		if codec == "" {
			codec = meta.Codec
		}
		if codec != "" {
			masterFields["codec"] = map[string]interface{}{"value": codec}
		} else {
			res.warning("codec omitted: no stsd sample entry recognised and the caller supplied no hint")
		}
		if meta.VideoFrameRate != nil {
			masterFields["videoFrameRate"] = map[string]interface{}{
				"value": int64(*meta.VideoFrameRate + 0.5),
			}
		}
	}

	if meta.Width > 0 {
		masterFields["resOriginalWidth"] = map[string]interface{}{"value": meta.Width}
	}
	if meta.Height > 0 {
		masterFields["resOriginalHeight"] = map[string]interface{}{"value": meta.Height}
	}

	// Attach Medium Derivative
	if medReceipt != nil {
		masterFields["resJPEGMedRes"] = map[string]interface{}{
			"value": map[string]interface{}{
				"size":              medReceipt.Size,
				"fileChecksum":      medReceipt.FileChecksum,
				"referenceChecksum": medReceipt.ReferenceChecksum,
				"wrappingKey":       medReceipt.WrappingKey,
				"receipt":           medReceipt.Receipt,
			},
		}
		masterFields["resJPEGMedFingerprint"] = map[string]interface{}{"value": medReceipt.FileChecksum}
		masterFields["resJPEGMedFileSize"] = map[string]interface{}{"value": medReceipt.Size}
		masterFields["resJPEGMedFileType"] = map[string]interface{}{"value": utiForJPEG}
		if meta.MedWidth > 0 {
			masterFields["resJPEGMedWidth"] = map[string]interface{}{"value": meta.MedWidth}
		}
		if meta.MedHeight > 0 {
			masterFields["resJPEGMedHeight"] = map[string]interface{}{"value": meta.MedHeight}
		}
	}

	// Attach Thumbnail Derivative
	if thumbReceipt != nil {
		masterFields["resJPEGThumbRes"] = map[string]interface{}{
			"value": map[string]interface{}{
				"size":              thumbReceipt.Size,
				"fileChecksum":      thumbReceipt.FileChecksum,
				"referenceChecksum": thumbReceipt.ReferenceChecksum,
				"wrappingKey":       thumbReceipt.WrappingKey,
				"receipt":           thumbReceipt.Receipt,
			},
		}
		masterFields["resJPEGThumbFingerprint"] = map[string]interface{}{"value": thumbReceipt.FileChecksum}
		masterFields["resJPEGThumbFileSize"] = map[string]interface{}{"value": thumbReceipt.Size}
		masterFields["resJPEGThumbFileType"] = map[string]interface{}{"value": utiForJPEG}
		if meta.ThumbWidth > 0 {
			masterFields["resJPEGThumbWidth"] = map[string]interface{}{"value": meta.ThumbWidth}
		}
		if meta.ThumbHeight > 0 {
			masterFields["resJPEGThumbHeight"] = map[string]interface{}{"value": meta.ThumbHeight}
		}
	}

	// Attach Live Photo Companion Video
	if vidReceipt != nil {
		masterFields["resOriginalVidComplRes"] = map[string]interface{}{
			"value": map[string]interface{}{
				"size":              vidReceipt.Size,
				"fileChecksum":      vidReceipt.FileChecksum,
				"referenceChecksum": vidReceipt.ReferenceChecksum,
				"wrappingKey":       vidReceipt.WrappingKey,
				"receipt":           vidReceipt.Receipt,
			},
		}
		masterFields["resOriginalVidComplFingerprint"] = map[string]interface{}{"value": vidReceipt.FileChecksum}
		masterFields["resOriginalVidComplFileSize"] = map[string]interface{}{"value": vidReceipt.Size}
		masterFields["resOriginalVidComplFileType"] = map[string]interface{}{"value": "com.apple.quicktime-movie"}
		if meta.VidComplWidth > 0 {
			masterFields["resOriginalVidComplWidth"] = map[string]interface{}{"value": meta.VidComplWidth}
		}
		if meta.VidComplHeight > 0 {
			masterFields["resOriginalVidComplHeight"] = map[string]interface{}{"value": meta.VidComplHeight}
		}
	}

	// Attach the metadata block.
	//
	// Two different payloads, two different `mediaMetaDataType` values:
	//   * photos  -> a `CGImageProperties` binary plist (EXIF/GPS)
	//   * videos  -> an `AVAssetProxy` plist wrapping the `ftyp`+`moov` boxes, which is where the
	//                client reads duration and track layout from
	//
	// Both builders return ok=false when there is nothing to write **or when encoding failed**.
	// Omitting is always the right fallback: a missing block only hides metadata, whereas a
	// malformed one is what made every asset undisplayable in ISS-040.
	if isVideo {
		if videoMetaOK {
			masterFields["mediaMetaDataEnc"] = map[string]interface{}{"value": videoMetaEnc}
			masterFields["mediaMetaDataType"] = map[string]interface{}{"value": "AVAssetProxy"}
		} else {
			// Surface it instead of shipping a record whose duration and track layout are
			// invisible. This is how the missing `AVAssetProxy` stayed undiagnosed for a round.
			res.warning("mediaMetaDataEnc omitted: movie header (ftyp+moov) could not be extracted")
		}
	} else if enc, ok := buildMediaMetaDataEnc(meta); ok {
		masterFields["mediaMetaDataEnc"] = map[string]interface{}{"value": enc}
		masterFields["mediaMetaDataType"] = map[string]interface{}{"value": "CGImageProperties"}
	} else {
		res.warning("mediaMetaDataEnc omitted: no usable EXIF, or the plist encoder rejected the payload")
	}

	assetFields := map[string]interface{}{
		"masterRef": map[string]interface{}{
			"value": map[string]interface{}{
				"recordName": mmcsChecksum,
				"action":     "DELETE_SELF",
			},
			"type": "REFERENCE",
		},
		"assetDate": map[string]interface{}{
			"value": meta.AssetDate,
		},
		"addedDate": map[string]interface{}{
			"value": nowMs,
		},
		"isFavorite": map[string]interface{}{
			"value": 0,
		},
		"isHidden": map[string]interface{}{
			"value": 0,
		},
		"isDeleted": map[string]interface{}{
			"value": 0,
		},
		"orientation": map[string]interface{}{
			"value": meta.Orientation,
		},

		// Present on every Apple-created `CPLAsset`, missing from ours (ISS-040).
		// `assetSubtypeV2` distinguishes plain photos from Live Photos / portraits /
		// panoramas; `0` is the plain-photo value measured on a web-uploaded HEIC.
		"assetSubtypeV2": map[string]interface{}{
			"value": 0,
		},
		"customRenderedValue": map[string]interface{}{
			"value": 0,
		},
		// Bracketed by `addedDate`/`assetDate`, this is what lets the client render
		// “19:00” instead of an hour-shifted time. Encoded as base64 "GMT+08:00".
		"timeZoneNameEnc": map[string]interface{}{
			"value": timeZoneNameEnc(meta.AssetDate),
		},
		"timeZoneOffset": map[string]interface{}{
			"value": timeZoneOffsetSeconds(meta.AssetDate),
		},
	}
	if meta.IsLivePhoto && vidReceipt != nil {
		// Live Photo assetSubtypeV2 = 2
		assetFields["assetSubtypeV2"] = map[string]interface{}{"value": 2}
		if meta.MediaGroupId != "" {
			assetFields["mediaGroupId"] = map[string]interface{}{"value": meta.MediaGroupId}
		}
		if meta.VidComplDurValue > 0 {
			assetFields["vidComplDurValue"] = map[string]interface{}{"value": meta.VidComplDurValue}
			durScale := meta.VidComplDurScale
			if durScale <= 0 {
				durScale = 1000000000
			}
			assetFields["vidComplDurScale"] = map[string]interface{}{"value": durScale}
		}
		if meta.VidComplDispValue > 0 {
			assetFields["vidComplDispValue"] = map[string]interface{}{"value": meta.VidComplDispValue}
			dispScale := meta.VidComplDispScale
			if dispScale <= 0 {
				dispScale = 1000000000
			}
			assetFields["vidComplDispScale"] = map[string]interface{}{"value": dispScale}
		}
		assetFields["vidComplVisibilityState"] = map[string]interface{}{"value": 0}
	} else if isVideo {
		// `assetSubtypeV2` is **0** on a plain video - confirmed against a genuine iPhone SE
		// capture. The `102` seen on a third-party import belongs to that importer, not to video
		// as a class, so copying it would mislabel every clip.
		assetFields["assetSubtypeV2"] = map[string]interface{}{"value": 0}
		// Apple stores the duration on the **CPLAsset**, not on the master, and it is what the
		// client renders as `0:07`. Without it the library shows `0:00` even though the moov box
		// (and therefore the real duration) is present in `mediaMetaDataEnc`.
		if meta.DurationMs > 0 {
			assetFields["duration"] = map[string]interface{}{"value": meta.DurationMs}
		}
	}
	// `locationEnc` is the photo library's own position index and is what the web info panel's map
	// is built from. `mediaMetaDataEnc`'s `{GPS}` dictionary is not enough on its own: ours was
	// valid and readable by our own client while the web still showed no map, and the one field a
	// native Apple record had that ours did not was this one (ISS-043).
	//
	// Applies to photos and videos alike - Apple writes it on every CPLAsset with a fix.
	locationEnc := meta.LocationEnc
	if locationEnc == "" {
		lat := optionalFloat(meta.Lat)
		lon := optionalFloat(meta.Lon)
		if lat != 0 || lon != 0 {
			locationEnc = BuildLocationEnc(
				lat, lon,
				optionalFloat(meta.Altitude),
				0, 0, // course, speed: not available from EXIF
				0, 0, // horizontal / vertical accuracy: not available either
				meta.AssetDate,
			)
		}
	}
	if locationEnc != "" {
		assetFields["locationEnc"] = map[string]interface{}{"value": locationEnc}
	} else if meta.Lat != nil || meta.Lon != nil {
		res.warning("locationEnc omitted: coordinates present but the plist encoder rejected them")
	}

	modifyPayload := map[string]interface{}{
		"operations": []map[string]interface{}{
			{
				"operationType": "create",
				"record": map[string]interface{}{
					"recordName": mmcsChecksum,
					"recordType": "CPLMaster",
					"fields":     masterFields,
				},
			},
			{
				"operationType": "create",
				"record": map[string]interface{}{
					"recordName": assetUuid,
					"recordType": "CPLAsset",
					"fields":     assetFields,
				},
			},
		},
		"zoneID": map[string]interface{}{
			"zoneName": "PrimarySync",
		},
		"atomic": true,
	}

	modifyBodyBytes, _ := json.Marshal(modifyPayload)
	modifyReq, err := s.NewRequest("POST", s.photosBaseURL("records/modify"), string(modifyBodyBytes))
	if err != nil {
		return makeUploadError("MODIFY_REQ_BUILD_ERROR", err.Error())
	}

	modifyResp, err := s.Client.Do(modifyReq)
	if err != nil {
		return makeUploadError("MODIFY_HTTP_ERROR", err.Error())
	}
	defer modifyResp.Body.Close()

	modifyRespBuf := new(bytes.Buffer)
	modifyRespBuf.ReadFrom(modifyResp.Body)
	modifyRespStr := modifyRespBuf.String()

	if modifyResp.StatusCode != 200 {
		if strings.Contains(modifyRespStr, "QUOTA_EXCEEDED") {
			return makeUploadError("QUOTA_EXCEEDED", "iCloud storage space quota exceeded")
		}
		failedErr := makeUploadError("MODIFY_FAILED", fmt.Sprintf("Status %d: %s", modifyResp.StatusCode, modifyRespStr))
		// Surface per-record failures even on a non-200 transport status: CloudKit frequently
		// reports the real cause per record and leaves the HTTP status generic.
		if msg := extractRecordErrors(modifyRespStr); msg != "" {
			failedErr = makeUploadError("MODIFY_RECORD_FAILED", msg)
		}
		return failedErr
	}

	// **CloudKit returns HTTP 200 even when individual records failed.**
	//
	// The interesting payload lives in `records[].serverErrorCode` / `records[].reason`. Without
	// checking it, a rejected create looks exactly like a success — which is how an earlier round
	// of uploads was recorded as `SUCCESS` in `upload_queue` while every asset was undisplayable
	// on Apple's side. Never collapse "status == 200" into "done".
	if msg := extractRecordErrors(modifyRespStr); msg != "" {
		return makeUploadError("MODIFY_RECORD_FAILED", msg)
	}

	res.MasterID = mmcsChecksum
	res.AssetID = assetUuid
	res.LocalSha256 = sha256Base64
	resBytes, _ := json.Marshal(res)
	return string(resBytes)
}

// warning records an optional field that was skipped, so the caller can log it.
func (r *UploadResult) warning(msg string) {
	r.Warnings = append(r.Warnings, msg)
}

// requestAssetUploadURLs asks `/assets/upload` for one single-file upload URL per field.
// Returns field name -> URL, or a ready-made error JSON.
func requestAssetUploadURLs(s *Session, tokensReq []map[string]interface{}) (map[string]string, string) {
	bodyBytes, _ := json.Marshal(map[string]interface{}{
		"tokens": tokensReq,
		"zoneID": map[string]interface{}{"zoneName": "PrimarySync"},
	})
	req, err := s.NewRequest("POST", s.photosBaseURL("assets/upload"), string(bodyBytes))
	if err != nil {
		return nil, makeUploadError("REQUEST_BUILD_ERROR", err.Error())
	}
	resp, err := s.Client.Do(req)
	if err != nil {
		return nil, makeUploadError("ASSETS_UPLOAD_HTTP_ERROR", err.Error())
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		buf := new(bytes.Buffer)
		buf.ReadFrom(resp.Body)
		return nil, makeUploadError("ASSETS_UPLOAD_FAILED", fmt.Sprintf("Status %d: %s", resp.StatusCode, buf.String()))
	}
	var result map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, makeUploadError("JSON_DECODE_ERROR", err.Error())
	}
	tokens, ok := result["tokens"].([]interface{})
	if !ok || len(tokens) == 0 {
		return nil, makeUploadError("NO_TOKENS_RETURNED", fmt.Sprintf("No upload tokens returned: %+v", result))
	}
	urls := make(map[string]string)
	for _, tok := range tokens {
		tokMap, ok := tok.(map[string]interface{})
		if !ok {
			continue
		}
		fn, _ := tokMap["fieldName"].(string)
		u, _ := tokMap["url"].(string)
		if fn != "" && u != "" {
			urls[fn] = u
		}
	}
	return urls, ""
}

// photosRegionLimited reports whether this session's Photos backend is 云上贵州, where the web
// upload gateway caps a single body at 1 GiB (ISS-057). Host suffix only, never a substring
// (AGENTS §6.7.9); an unknown host fails open (false) so the normal path is tried.
func photosRegionLimited(s *Session) bool {
	if s == nil {
		return false
	}
	s.mu.RLock()
	ck := s.CKDatabaseURL
	docs := docsEndpointFromAccountInfo(s.AccountInfoJSON)
	s.mu.RUnlock()
	for _, u := range []string{ck, docs} {
		if u == "" {
			continue
		}
		host := u
		if i := strings.Index(host, "://"); i >= 0 {
			host = host[i+3:]
		}
		if i := strings.IndexAny(host, "/?"); i >= 0 {
			host = host[:i]
		}
		if i := strings.LastIndexByte(host, ':'); i >= 0 {
			host = host[:i]
		}
		if strings.HasSuffix(strings.ToLower(host), ".icloud.com.cn") {
			return true
		}
	}
	return false
}
