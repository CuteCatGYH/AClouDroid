package icloudcore

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// DriveUploadProgress tracks the live bytes transferred and percentage for a given upload key.
//
// Phase exists because "bytesMoved" means two different things during an upload and the UI has
// to tell them apart:
//
//   - PHASE_HASH   — reading the whole file to compute SHA-256. No network traffic at all.
//   - PHASE_UPLOAD — streaming bytes to CWS.
//
// Without it the UI showed "传输中..." for the entire hashing pass, because the poller only
// reported when bytes had been read and the hash phase reported 0 the whole way.
type DriveUploadProgress struct {
	BytesUploaded int64   `json:"bytesUploaded"`
	TotalBytes    int64   `json:"totalBytes"`
	Progress      float64 `json:"progress"` // 0.0 to 100.0
	Phase         string  `json:"phase"`    // PHASE_HASH / PHASE_UPLOAD / PHASE_COMMIT
}

const (
	PhaseHash   = "HASH"
	PhaseUpload = "UPLOAD"
	PhaseCommit = "COMMIT"
)

const (
	// cwsWatchdogInterval is how often the stall detector samples the byte counter.
	cwsWatchdogInterval = 5 * time.Second
	// cwsIdleTimeout aborts an upload only when zero bytes moved for this long. Chosen well
	// above any plausible TCP retransmit window so a momentarily congested link is not
	// mistaken for a dead one.
	cwsIdleTimeout = 120 * time.Second
)

var (
	uploadProgressMap   = make(map[string]DriveUploadProgress)
	uploadProgressMutex sync.RWMutex
)

type progressReader struct {
	reader     io.Reader
	total      int64
	read       int64
	key        string
	lastUpdate time.Time
	// mu guards read. The CWS watchdog goroutine samples it concurrently to tell a slow
	// upload from a dead one; without this it is a data race under -race.
	mu sync.Mutex
}

func (pr *progressReader) Read(p []byte) (int, error) {
	n, err := pr.reader.Read(p)
	if n > 0 {
		pr.mu.Lock()
		pr.read += int64(n)
		pr.mu.Unlock()
		now := time.Now()
		if now.Sub(pr.lastUpdate) >= 150*time.Millisecond || pr.read == pr.total || err != nil {
			pr.lastUpdate = now
			updateDriveUploadProgress(pr.key, pr.read, pr.total)
		}
	}
	return n, err
}

func updateDriveUploadProgress(key string, read int64, total int64) {
	updateDriveUploadProgressPhase(key, read, total, PhaseUpload)
}

func updateDriveUploadProgressPhase(key string, read int64, total int64, phase string) {
	if key == "" {
		return
	}
	var pct float64
	if total > 0 {
		pct = (float64(read) / float64(total)) * 100.0
		if pct > 100.0 {
			pct = 100.0
		}
	}
	uploadProgressMutex.Lock()
	uploadProgressMap[key] = DriveUploadProgress{
		BytesUploaded: read,
		TotalBytes:    total,
		Progress:      pct,
		Phase:         phase,
	}
	uploadProgressMutex.Unlock()
}

// GetDriveUploadProgress returns current upload progress JSON for a key (e.g. filePath or filename).
//
// Lookup is exact-first. The fuzzy fallback only exists for callers that hold a bare filename
// rather than a path, and it is deliberately limited to `filepath.Base` equality: the previous
// version also matched on `HasSuffix` in both directions, which made `/a/b/report.pdf` and
// `/c/d/report.pdf` collide — two concurrent uploads would then report each other's byte counts.
func GetDriveUploadProgress(key string) string {
	key = strings.TrimSpace(key)
	uploadProgressMutex.RLock()
	prog, ok := uploadProgressMap[key]
	if !ok && key != "" {
		base := filepath.Base(key)
		for k, p := range uploadProgressMap {
			if filepath.Base(k) == base {
				prog = p
				ok = true
				break
			}
		}
	}
	uploadProgressMutex.RUnlock()

	if !ok {
		b, _ := json.Marshal(DriveUploadProgress{BytesUploaded: 0, TotalBytes: 0, Progress: 0, Phase: PhaseHash})
		return string(b)
	}
	b, _ := json.Marshal(prog)
	return string(b)
}

func clearDriveUploadProgress(key string) {
	if key == "" {
		return
	}
	uploadProgressMutex.Lock()
	delete(uploadProgressMap, key)
	uploadProgressMutex.Unlock()
}

// hashReaderProgress wraps the file during the SHA-256 pass so the UI can show a real
// "计算校验和 42%" instead of a frozen "传输中...".
//
// This is the fix for the "卡在传输中好几分钟" report: a 178 MB APK takes a measurable amount
// of time to hash on a phone, and the old code reported `read = 0` for that entire window
// (updateDriveUploadProgress was only called before hashing and again inside the CWS
// progressReader, never during the hash itself).
type hashProgressReader struct {
	reader     io.Reader
	total      int64
	read       int64
	key        string
	lastUpdate time.Time
}

func (hr *hashProgressReader) Read(p []byte) (int, error) {
	n, err := hr.reader.Read(p)
	if n > 0 {
		hr.read += int64(n)
		now := time.Now()
		if now.Sub(hr.lastUpdate) >= 150*time.Millisecond || err != nil {
			hr.lastUpdate = now
			updateDriveUploadProgressPhase(hr.key, hr.read, hr.total, PhaseHash)
		}
	}
	return n, err
}

// computeFileSha256Base64WithProgress mirrors computeFileSha256Base64 but reports its progress.
// The shared helper is left untouched because Photos' upload path also depends on it and has no
// progress surface of its own.
func computeFileSha256Base64WithProgress(f *os.File, size int64, key string) (string, error) {
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	h := sha256.New()
	hr := &hashProgressReader{reader: f, total: size, key: key}
	if _, err := io.Copy(h, hr); err != nil {
		return "", err
	}
	updateDriveUploadProgressPhase(key, size, size, PhaseHash)
	return base64.StdEncoding.EncodeToString(h.Sum(nil)), nil
}
type DriveUploadResult struct {
	ResultBase
	DocumentID   string `json:"documentId,omitempty"`
	DrivewsID    string `json:"drivewsId,omitempty"`
	FileChecksum string `json:"fileChecksum,omitempty"`
	Filename     string `json:"filename,omitempty"`
	Filesize     int64  `json:"filesize,omitempty"`
}

func makeDriveUploadError(code string, httpStatus int, msg string) string {
	res := DriveUploadResult{
		ResultBase: failBase(code, httpStatus, msg),
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// UploadDriveItem performs a zero-memory streaming upload of a file to CloudDocs using an open file descriptor (fd).
func UploadDriveItem(fd int, filename string, mimeType string, targetFolderPath string) string {
	if fd < 0 {
		return makeDriveUploadError(ErrCodeParse, 0, "Invalid file descriptor")
	}
	f := os.NewFile(uintptr(fd), filename)
	if f == nil {
		return makeDriveUploadError(ErrCodeParse, 0, "Failed to create os.File from file descriptor")
	}
	return uploadDriveItemFromFile(f, filename, mimeType, targetFolderPath)
}

// UploadDriveItemByPath performs a zero-memory streaming upload of a file to CloudDocs given a local file path.
func UploadDriveItemByPath(filePath string, filename string, mimeType string, targetFolderPath string) string {
	filePath = strings.TrimSpace(filePath)
	if filePath == "" {
		return makeDriveUploadError(ErrCodeNotFound, 0, "File path is empty")
	}
	f, err := os.Open(filePath)
	if err != nil {
		return makeDriveUploadError(ErrCodeNotFound, 0, "Failed to open file: "+err.Error())
	}
	defer f.Close()

	if filename == "" {
		filename = filepath.Base(filePath)
	}

	return uploadDriveItemFromFile(f, filename, mimeType, targetFolderPath)
}

func uploadDriveItemFromFile(f *os.File, filename string, mimeType string, targetFolderPath string) string {
	s := GetSession()
	if s == nil {
		return makeDriveUploadError(ErrCodeAuthExpired, 0, "No active session in Go core")
	}

	filePathKey := f.Name()
	defer clearDriveUploadProgress(filePathKey)

	s.mu.RLock()
	rawAccountInfo := s.AccountInfoJSON
	s.mu.RUnlock()

	docsURL := docsEndpointFromAccountInfo(rawAccountInfo)
	if docsURL == "" {
		docsURL = s.DrivewsURL
	}
	if docsURL == "" {
		return makeDriveUploadError(ErrCodeAuthExpired, 0, "docws endpoint is not set")
	}

	targetFolderPath = strings.TrimSpace(targetFolderPath)
	if targetFolderPath == "" {
		targetFolderPath = "/"
	}
	if !strings.HasPrefix(targetFolderPath, "/") {
		targetFolderPath = "/" + targetFolderPath
	}

	filename = strings.TrimSpace(filename)
	if filename == "" {
		filename = "untitled"
	}

	fileInfo, err := f.Stat()
	if err != nil {
		return makeDriveUploadError(ErrCodeParse, 0, "Failed to stat file: "+err.Error())
	}
	fileSize := fileInfo.Size()
	// Announce the hash phase up-front with a known total so the UI can render a determinate
	// bar from the first tick. The old code called this with the same arguments but then never
	// updated it until hashing finished, which is exactly the "frozen at 0" symptom.
	updateDriveUploadProgressPhase(filePathKey, 0, fileSize, PhaseHash)

	if _, err := f.Seek(0, 0); err != nil {
		return makeDriveUploadError(ErrCodeParse, 0, "Failed to seek file: "+err.Error())
	}

	sha256Base64, err := computeFileSha256Base64WithProgress(f, fileSize, filePathKey)
	if err != nil {
		return makeDriveUploadError(ErrCodeParse, 0, "Failed to hash file: "+err.Error())
	}

	if mimeType == "" {
		mimeType = "application/octet-stream"
	}

	// 1. Request Upload Token via /upload/web
	uploadWebEndpoint := fmt.Sprintf("%s/ws/com.apple.CloudDocs/upload/web", strings.TrimRight(docsURL, "/"))
	uploadWebPayload := map[string]interface{}{
		"filesize":      fileSize,
		"filesignature": sha256Base64,
		"type":          "FILE",
		"filename":      filename,
		"content_type":  mimeType,
	}
	payloadBytes, _ := json.Marshal(uploadWebPayload)

	req1, err := s.NewRequest(http.MethodPost, uploadWebEndpoint, string(payloadBytes))
	if err != nil {
		return makeDriveUploadError(ClassifyNetErr(err), 0, "Failed to build upload token request: "+err.Error())
	}

	resp1, err := s.Client.Do(req1)
	if err != nil {
		return makeDriveUploadError(ClassifyNetErr(err), 0, "Upload token request failed: "+err.Error())
	}
	defer resp1.Body.Close()

	s.ExtractAndSaveCookies(resp1)
	body1, err := io.ReadAll(resp1.Body)
	if err != nil {
		return makeDriveUploadError(ErrCodeNetwork, resp1.StatusCode, "Failed to read upload token response: "+err.Error())
	}

	if resp1.StatusCode != http.StatusOK {
		return makeDriveUploadError(ClassifyHTTPStatus(resp1.StatusCode), resp1.StatusCode,
			fmt.Sprintf("Upload token request failed with HTTP %d: %s", resp1.StatusCode, string(body1)))
	}

	var uploadWebResponse []struct {
		DocumentID string `json:"document_id"`
		URL        string `json:"url"`
	}
	if err := json.Unmarshal(body1, &uploadWebResponse); err != nil || len(uploadWebResponse) == 0 {
		return makeDriveUploadError(ErrCodeParse, resp1.StatusCode, "Failed to parse upload token response")
	}

	docID := uploadWebResponse[0].DocumentID
	uploadURL := uploadWebResponse[0].URL

	if docID == "" || uploadURL == "" {
		return makeDriveUploadError(ErrCodeParse, resp1.StatusCode, "Upload token response contained empty document_id or url")
	}

	// 2. Stream file bytes to CWS upload URL with ProgressReader & 256KB socket transport
	if _, err := f.Seek(0, 0); err != nil {
		return makeDriveUploadError(ErrCodeParse, 0, "Failed to seek file for streaming: "+err.Error())
	}

	// Reset to 0 for the upload phase. Without this the bar would jump backwards from 100%
	// (hash complete) to 0% (upload starting) and look like a stall.
	updateDriveUploadProgressPhase(filePathKey, 0, fileSize, PhaseUpload)

	pr := &progressReader{
		reader: f,
		total:  fileSize,
		key:    filePathKey,
	}

	cwsReq, err := http.NewRequest(http.MethodPost, uploadURL, pr)
	if err != nil {
		return makeDriveUploadError(ClassifyNetErr(err), 0, "Failed to build CWS upload request: "+err.Error())
	}
	cwsReq.Header.Set("Content-Type", mimeType)
	cwsReq.ContentLength = fileSize

	cwsTransport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		WriteBufferSize:       256 * 1024,
		ReadBufferSize:        256 * 1024,
	}

	cwsClient := &http.Client{
		Transport: cwsTransport,
		// No blanket Timeout: it is a *total* deadline covering the whole body write, so a
		// 1 GB file at 2 MB/s (≈8.5 min) would always be killed mid-stream no matter how
		// healthy the connection is. Idle-stall detection is what we actually want, and it
		// is enforced by the watchdog goroutine below.
		Timeout: 0,
	}

	// Watchdog: abort the upload only when no bytes have moved for cwsIdleTimeout.
	// This distinguishes "slow but alive" (keep going) from "connection died silently"
	// (fail fast and let the caller retry).
	swapCtx, cancelCtx := context.WithCancel(context.Background())
	defer cancelCtx()
	cwsReq = cwsReq.WithContext(swapCtx)

	var lastBytes int64
	watchdogDone := make(chan struct{})
	go func() {
		defer close(watchdogDone)
		ticker := time.NewTicker(cwsWatchdogInterval)
		defer ticker.Stop()
		stalled := time.Duration(0)
		for {
			select {
			case <-swapCtx.Done():
				return
			case <-ticker.C:
				pr.mu.Lock()
				cur := pr.read
				pr.mu.Unlock()
				if cur == lastBytes {
					stalled += cwsWatchdogInterval
					if stalled >= cwsIdleTimeout {
						cancelCtx()
						return
					}
				} else {
					lastBytes = cur
					stalled = 0
				}
			}
		}
	}()

	cwsResp, err := cwsClient.Do(cwsReq)
	cancelCtx()
	<-watchdogDone
	if err != nil {
		return makeDriveUploadError(ClassifyNetErr(err), 0, "CWS streaming upload failed: "+err.Error())
	}
	defer cwsResp.Body.Close()

	cwsBody, err := io.ReadAll(cwsResp.Body)
	if err != nil {
		return makeDriveUploadError(ErrCodeNetwork, cwsResp.StatusCode, "Failed to read CWS response: "+err.Error())
	}

	if cwsResp.StatusCode != http.StatusOK && cwsResp.StatusCode != http.StatusCreated {
		return makeDriveUploadError(ClassifyHTTPStatus(cwsResp.StatusCode), cwsResp.StatusCode,
			fmt.Sprintf("CWS streaming upload failed with HTTP %d: %s", cwsResp.StatusCode, string(cwsBody)))
	}

	var cwsParsed struct {
		SingleFile struct {
			FileChecksum      string `json:"fileChecksum"`
			Receipt           string `json:"receipt"`
			ReferenceChecksum string `json:"referenceChecksum"`
			WrappingKey       string `json:"wrappingKey"`
		} `json:"singleFile"`
	}
	_ = json.Unmarshal(cwsBody, &cwsParsed)

	// 3. Register / Commit document in CloudDocs (/update/documents)
	// Tell the UI we are no longer moving bytes; the commit round trip is short but if it
	// hangs the bar should not keep claiming "99% uploading".
	updateDriveUploadProgressPhase(filePathKey, fileSize, fileSize, PhaseCommit)

	targetFilePath := filepath.Join(targetFolderPath, filename)
	if !strings.HasPrefix(targetFilePath, "/") {
		targetFilePath = "/" + targetFilePath
	}

	updateEndpoint := fmt.Sprintf("%s/ws/com.apple.CloudDocs/update/documents", strings.TrimRight(docsURL, "/"))
	updatePayload := map[string]interface{}{
		"command":     "add_file",
		"document_id": docID,
		"path": map[string]interface{}{
			"path": targetFilePath,
		},
		"data": map[string]interface{}{
			"signature":           cwsParsed.SingleFile.FileChecksum,
			"receipt":             cwsParsed.SingleFile.Receipt,
			"size":                fileSize,
			"reference_signature": cwsParsed.SingleFile.ReferenceChecksum,
			"wrapping_key":        cwsParsed.SingleFile.WrappingKey,
		},
	}
	uBytes, _ := json.Marshal(updatePayload)

	upReq, err := s.NewRequest(http.MethodPost, updateEndpoint, string(uBytes))
	if err != nil {
		return makeDriveUploadError(ClassifyNetErr(err), 0, "Failed to build document commit request: "+err.Error())
	}

	upResp, err := s.Client.Do(upReq)
	if err != nil {
		return makeDriveUploadError(ClassifyNetErr(err), 0, "Document commit request failed: "+err.Error())
	}
	defer upResp.Body.Close()

	s.ExtractAndSaveCookies(upResp)
	upBody, err := io.ReadAll(upResp.Body)
	if err != nil {
		return makeDriveUploadError(ErrCodeNetwork, upResp.StatusCode, "Failed to read document commit response: "+err.Error())
	}

	if upResp.StatusCode != http.StatusOK {
		return makeDriveUploadError(ClassifyHTTPStatus(upResp.StatusCode), upResp.StatusCode,
			fmt.Sprintf("Document commit failed with HTTP %d: %s", upResp.StatusCode, string(upBody)))
	}

	drivewsID := fmt.Sprintf("FILE::com.apple.CloudDocs::%s", docID)

	res := DriveUploadResult{
		ResultBase:   okBase(),
		DocumentID:   docID,
		DrivewsID:    drivewsID,
		FileChecksum: cwsParsed.SingleFile.FileChecksum,
		Filename:     filename,
		Filesize:     fileSize,
	}
	resBytes, _ := json.Marshal(res)
	return string(resBytes)
}
