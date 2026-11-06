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

	// cwsTotalTimeout bounds the WHOLE CWS body write.
	//
	// cwsIdleTimeout alone is not enough: the watchdog only fires when the byte counter stops
	// moving, so an upload that drip-feeds a few hundred bytes per tick can run unbounded. Observed
	// on device: several consecutive uploads each failed with HTTP 408 after almost exactly two
	// minutes — the CWS edge was enforcing its own deadline while we happily waited. This ceiling
	// sits below that edge deadline so WE decide first and can name the reason.
	cwsTotalTimeout = 100 * time.Second

	// commitTimeout bounds /update/documents (the CloudDocs commit round trip).
	//
	// This used to inherit Session.Client's blanket `Timeout: 30s`, which is shared with every
	// CloudKit query and therefore cannot simply be raised. The commit is a small JSON POST that
	// normally returns in well under a second, but it runs AFTER the body has been fully streamed —
	// so on device a stalled commit meant the progress bar sat at 100% while the file appeared to
	// upload forever, and the eventual failure discarded the whole transfer.
	commitTimeout = 120 * time.Second

	// progressEntryTTL is how long a finished/failed upload's progress entry is kept after its
	// last update before being swept.
	//
	// Entries are otherwise only removed by uploadDriveItemFromFile's `defer`. A JNI call that never
	// returns (process killed mid-stream), an empty key, or a bare-filename lookup all leave a stale
	// entry behind, and GetDriveUploadProgress's fuzzy `filepath.Base` fallback then hands the NEXT
	// upload that abandoned counter. The UI read that as "still uploading" on a file that had not
	// started yet.
	progressEntryTTL = 5 * time.Minute
)

var (
	uploadProgressMap = make(map[string]DriveUploadProgress)
	// uploadProgressUpdatedAt mirrors uploadProgressMap's keys and records the last write, so
	// stale entries can be swept (see progressEntryTTL).
	uploadProgressUpdatedAt = make(map[string]time.Time)
	uploadProgressMutex     sync.RWMutex
)

// sweepLocked drops progress entries that have not been touched for progressEntryTTL.
//
// Caller must hold uploadProgressMutex for writing. This exists because the map had no expiry at
// all: an entry survived forever unless the owning upload reached its `defer`.
func sweepStaleProgressLocked(now time.Time) {
	for k, at := range uploadProgressUpdatedAt {
		if now.Sub(at) > progressEntryTTL {
			delete(uploadProgressUpdatedAt, k)
			delete(uploadProgressMap, k)
		}
	}
}

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
		cur := pr.read
		pr.mu.Unlock()
		now := time.Now()
		if now.Sub(pr.lastUpdate) >= 150*time.Millisecond || cur == pr.total || err != nil {
			pr.lastUpdate = now
			updateDriveUploadProgress(pr.key, cur, pr.total)
		}
	}
	return n, err
}

// currentRead returns the byte count transferred so far, safe for concurrent sampling.
func (pr *progressReader) currentRead() int64 {
	pr.mu.Lock()
	defer pr.mu.Unlock()
	return pr.read
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
	now := time.Now()
	uploadProgressMutex.Lock()
	sweepStaleProgressLocked(now)
	uploadProgressMap[key] = DriveUploadProgress{
		BytesUploaded: read,
		TotalBytes:    total,
		Progress:      pct,
		Phase:         phase,
	}
	uploadProgressUpdatedAt[key] = now
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
	now := time.Now()
	uploadProgressMutex.RLock()
	prog, ok := uploadProgressMap[key]
	stale := ok && now.Sub(uploadProgressUpdatedAt[key]) > progressEntryTTL
	if !ok && key != "" {
		// Report the MOST RECENT basename match, not the first one the map happens to yield.
		// Two uploads of the same filename in different folders used to race on this loop and the
		// UI could sample either counter.
		base := filepath.Base(key)
		var newest time.Time
		for k, p := range uploadProgressMap {
			if filepath.Base(k) != base {
				continue
			}
			at := uploadProgressUpdatedAt[k]
			if at.After(newest) {
				newest = at
				prog = p
				ok = true
			}
		}
		if ok && now.Sub(newest) > progressEntryTTL {
			stale = true
		}
	}
	uploadProgressMutex.RUnlock()

	// A stale entry must read as "idle at 0%", never as a live counter that a later upload then
	// inherits (see progressEntryTTL).
	if !ok || stale {
		b, _ := json.Marshal(DriveUploadProgress{BytesUploaded: 0, TotalBytes: 0, Progress: 0, Phase: PhaseHash})
		return string(b)
	}
	b, _ := json.Marshal(prog)
	return string(b)
}

// ClearDriveUploadProgress drops the live progress entry for a key.
//
// Exported for the Kotlin layer: the poll loop runs in a different coroutine than the blocking
// JNI upload call, so on the failure paths where the Go function returns early (or the coroutine
// is cancelled) nothing inside Go ever reaches the `defer` that clears the entry. Without this the
// banner kept showing the previous file's byte count for the next one.
func ClearDriveUploadProgress(key string) {
	clearDriveUploadProgress(strings.TrimSpace(key))
}

func clearDriveUploadProgress(key string) {
	if key == "" {
		return
	}
	uploadProgressMutex.Lock()
	delete(uploadProgressMap, key)
	delete(uploadProgressUpdatedAt, key)
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
		// A total deadline, sized above what a healthy transfer needs (100 s at 1 MB/s is 100 MB)
		// and below the CWS edge's own ~120 s cutoff, so a stall is reported by us instead of
		// surfacing as an opaque HTTP 408 after two minutes of apparent progress.
		Timeout: cwsTotalTimeout,
	}

	// Watchdog: abort the upload only when no bytes have moved for cwsIdleTimeout.
	// This distinguishes "slow but alive" (keep going) from "connection died silently"
	// (fail fast and let the caller retry).
	uploadCtx, cancelCtx := context.WithTimeout(context.Background(), cwsTotalTimeout)
	defer cancelCtx()
	cwsReq = cwsReq.WithContext(uploadCtx)

	var lastBytes int64
	var stalledFor time.Duration
	watchdogDone := make(chan struct{})
	go func() {
		defer close(watchdogDone)
		ticker := time.NewTicker(cwsWatchdogInterval)
		defer ticker.Stop()
		stalled := time.Duration(0)
		for {
			select {
			case <-uploadCtx.Done():
				return
			case <-ticker.C:
				pr.mu.Lock()
				cur := pr.read
				pr.mu.Unlock()
				if cur == lastBytes {
					stalled += cwsWatchdogInterval
					stalledFor = stalled
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
		// Name the deadline explicitly.
		//
		// Previously a stalled body was reported as "CWS streaming upload failed with HTTP 408",
		// which reads like the server refused the file. It was almost always a client-side stall,
		// and the UI paid for it by re-hashing and re-uploading instead of backing off.
		if uploadCtx.Err() == context.DeadlineExceeded {
			pr.mu.Lock()
			moved := pr.read
			pr.mu.Unlock()
			if stalledFor >= cwsIdleTimeout {
				return makeDriveUploadError(ErrCodeTimeout, 0,
					fmt.Sprintf("传输停滞：%s 内无任何字节变动（已传 %d/%d 字节）",
						cwsIdleTimeout, moved, fileSize))
			}
			return makeDriveUploadError(ErrCodeTimeout, 0,
				fmt.Sprintf("传输超时：超过 %s 仍未完成（已传 %d/%d 字节）",
					cwsTotalTimeout, moved, fileSize))
		}
		return makeDriveUploadError(ClassifyNetErr(err), 0, "CWS streaming upload failed: "+err.Error())
	}
	defer cwsResp.Body.Close()

	cwsBody, err := io.ReadAll(cwsResp.Body)
	if err != nil {
		return makeDriveUploadError(ErrCodeNetwork, cwsResp.StatusCode, "Failed to read CWS response: "+err.Error())
	}

	if cwsResp.StatusCode != http.StatusOK && cwsResp.StatusCode != http.StatusCreated {
		// A 408 from the edge means our body write did not finish in time. Report it as a timeout
		// so Kotlin backs off and retries instead of treating it like an auth or data failure.
		if cwsResp.StatusCode == http.StatusRequestTimeout {
			return makeDriveUploadError(ErrCodeTimeout, cwsResp.StatusCode,
				fmt.Sprintf("CWS 服务器报告传输超时（HTTP 408）：已传 %d/%d 字节，%s",
					pr.currentRead(), fileSize, string(cwsBody)))
		}
		return makeDriveUploadError(ClassifyHTTPStatus(cwsResp.StatusCode), cwsResp.StatusCode,
			fmt.Sprintf("CWS streaming upload failed with HTTP %d: %s", cwsResp.StatusCode, string(cwsBody)))
	}

	// Pin the bar at 100% for the upload phase before the commit starts. The reader stops one
	// update short whenever the last timed sample landed just before EOF, which left the UI at
	// 99% for the entire commit round trip.
	updateDriveUploadProgressPhase(filePathKey, fileSize, fileSize, PhaseUpload)

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

	// Run the commit through a client with its own deadline instead of Session.Client.
	//
	// Session.Client carries a blanket 30 s timeout shared with every CloudKit query, so it cannot
	// simply be raised. Inheriting it here was the worst of both worlds: a commit that needed longer
	// than 30 s failed, and the failure arrived only AFTER the entire body had already been uploaded
	// — which is why a 7 MB file could take minutes and still end in "upload failed".
	//
	// The transport is cloned so connection reuse and TLS settings stay identical.
	commitClient := &http.Client{Timeout: commitTimeout}
	if base, ok := s.Client.Transport.(*http.Transport); ok && base != nil {
		commitClient.Transport = base.Clone()
	}

	upResp, err := commitClient.Do(upReq)
	if err != nil {
		code := ClassifyNetErr(err)
		if code == ErrCodeTimeout {
			return makeDriveUploadError(code, 0,
				fmt.Sprintf("提交记录超时：文件已传完但 /update/documents 在 %s 内未返回", commitTimeout))
		}
		return makeDriveUploadError(code, 0, "Document commit request failed: "+err.Error())
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
