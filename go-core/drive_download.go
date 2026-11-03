package icloudcore

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
)

var (
	activeDownloads = make(map[string]context.CancelFunc)
	// cancelledIDs remembers cancellations that arrived BEFORE the download registered itself.
	//
	// Without it there is a real race: Kotlin taps "cancel" while the goroutine is still inside
	// `retrieveDownloadURL` (minting the signed URL), so no entry exists in activeDownloads yet.
	// CancelDownload would return false, the download would then register and proceed to fetch
	// the entire file — the exact symptom that made the cancel button look fake.
	cancelledIDs      = make(map[string]struct{})
	activeDownloadsMu sync.Mutex
)

// cancelledIDTTLMillis bounds how long a pre-registration cancellation is remembered, so the
// map cannot grow without limit if a caller cancels an id that is never downloaded.
const cancelledIDTTLMillis = 10 * 60 * 1000

// CancelDownload cancels an in-flight download by downloadID or destPath.
//
// Returns true when a live download was signalled. A `false` return does NOT mean the request
// was ignored: the id is also recorded so a download that starts a moment later aborts before
// transferring anything.
func CancelDownload(downloadID string) bool {
	downloadID = strings.TrimSpace(downloadID)
	if downloadID == "" {
		return false
	}

	activeDownloadsMu.Lock()
	cancel, ok := activeDownloads[downloadID]
	if !ok {
		cancelledIDs[downloadID] = struct{}{}
	}
	activeDownloadsMu.Unlock()

	if ok && cancel != nil {
		cancel()
		return true
	}
	return false
}

// isCancelledLocked reports (and clears) a pending cancellation for id. Callers hold the mutex.
func isCancelledLocked(id string) bool {
	if _, ok := cancelledIDs[id]; ok {
		delete(cancelledIDs, id)
		return true
	}
	return false
}

// clearCancelled drops a stale cancellation marker so it cannot affect a later, unrelated run.
func clearCancelled(id string) {
	activeDownloadsMu.Lock()
	delete(cancelledIDs, id)
	activeDownloadsMu.Unlock()
}

// DriveDownloadToken is the signed URL pair returned by `download/by_id`.
//
// Both URLs carry a signature in the query string and are therefore short-lived; the caller must
// treat them as opaque and re-mint rather than persist beyond the current download.
type DriveDownloadToken struct {
	DocumentID string `json:"document_id,omitempty"`
	ItemID     string `json:"item_id,omitempty"`
	OwnerDSID  int64  `json:"owner_dsid,omitempty"`
	/** Full-file URL. */
	URL string `json:"url,omitempty"`
	/** Thumbnail URL. Separate host (`cvws` vs `cws`) and independent expiry. */
	ThumbnailURL string `json:"thumbnailUrl,omitempty"`
	Size         int64  `json:"size,omitempty"`
}

type DriveDownloadTokenResult struct {
	ResultBase
	DocwsID string              `json:"docwsid,omitempty"`
	Token   *DriveDownloadToken `json:"token,omitempty"`
}

// RetrieveDownloadURL mints a signed download URL for one file.
//
// Endpoint: GET {docws}/ws/{zone}/download/by_id?document_id=<docwsid>
//
// The response nests the URLs under `data_token` (full file) and `thumbnail_token` (preview).
// Both are flattened into [DriveDownloadToken] here so the Kotlin layer never deals with the
// wrapper — the same approach `RetrieveItemDetails` takes with its `items` array.
func RetrieveDownloadURL(docwsID string) string {
	s := GetSession()
	if s == nil {
		return marshalDownloadToken(failBase(ErrCodeAuthExpired, 0, "No active session"), docwsID, nil)
	}

	s.mu.RLock()
	raw := s.AccountInfoJSON
	s.mu.RUnlock()

	docsURL := docsEndpointFromAccountInfo(raw)
	if docsURL == "" {
		return marshalDownloadToken(failBase(ErrCodeAuthExpired, 0, "docws endpoint is not set"), docwsID, nil)
	}

	docwsID = strings.TrimSpace(docwsID)
	if docwsID == "" {
		return marshalDownloadToken(failBase(ErrCodeNotFound, 0, "docwsid is empty"), docwsID, nil)
	}

	// Read the zone BEFORE stripping the prefix — the zone only exists in the fully-qualified
	// form. An APP_LIBRARY file lives in its app's zone, and asking the wrong zone's endpoint
	// returns 404 for a file that plainly exists.
	zone := zoneForDocwsID(docwsID)

	// `document_id` wants the bare uuid, not the `FILE::zone::uuid` form. Callers may pass either,
	// so strip the prefix rather than reject the fully-qualified one.
	docwsID = bareDocwsID(docwsID)

	endpoint := fmt.Sprintf("%s/ws/%s/download/by_id?document_id=%s",
		strings.TrimRight(docsURL, "/"), url.PathEscape(zone), url.QueryEscape(docwsID))

	req, err := s.NewRequest(http.MethodGet, endpoint, "")
	if err != nil {
		return marshalDownloadToken(failBase(ClassifyNetErr(err), 0, err.Error()), docwsID, nil)
	}
	req.Body = http.NoBody

	resp, err := s.Client.Do(req)
	if err != nil {
		return marshalDownloadToken(failBase(ClassifyNetErr(err), 0, err.Error()), docwsID, nil)
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return marshalDownloadToken(failBase(ErrCodeNetwork, resp.StatusCode, "read body: "+err.Error()), docwsID, nil)
	}

	if resp.StatusCode != http.StatusOK {
		preview := string(body)
		if len(preview) > 256 {
			preview = preview[:256] + "..."
		}
		return marshalDownloadToken(
			failBase(ClassifyHTTPStatus(resp.StatusCode), resp.StatusCode,
				fmt.Sprintf("HTTP %d: %s", resp.StatusCode, preview)), docwsID, nil)
	}

	var parsed struct {
		DocumentID string `json:"document_id"`
		ItemID     string `json:"item_id"`
		OwnerDSID  int64  `json:"owner_dsid"`
		DataToken  struct {
			URL string `json:"url"`
		} `json:"data_token"`
		ThumbnailToken struct {
			URL string `json:"url"`
		} `json:"thumbnail_token"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return marshalDownloadToken(failBase(ErrCodeParse, resp.StatusCode, "parse: "+err.Error()), docwsID, nil)
	}

	if parsed.DataToken.URL == "" {
		// A 200 with no `data_token.url` is how the API reports "this file has no downloadable
		// content" — e.g. an iWork bundle or a zero-byte placeholder. Reporting NOT_FOUND beats
		// handing the caller an empty URL it would then fail on with a confusing transport error.
		return marshalDownloadToken(
			failBase(ErrCodeNotFound, resp.StatusCode, "no data_token.url in response"), docwsID, nil)
	}

	return marshalDownloadToken(okBase(), docwsID, &DriveDownloadToken{
		DocumentID:   parsed.DocumentID,
		ItemID:       parsed.ItemID,
		OwnerDSID:    parsed.OwnerDSID,
		URL:          parsed.DataToken.URL,
		ThumbnailURL: parsed.ThumbnailToken.URL,
	})
}

type DriveDownloadResult struct {
	ResultBase
	DocwsID string `json:"docwsid,omitempty"`
	/** Bytes written, taken from the file on disk rather than from any header. */
	Written int64 `json:"written"`
	/** Path actually written. */
	Path string `json:"path,omitempty"`
}

// DownloadToFile streams a signed URL into `destPath`.
//
// ## Resumption & Cancellation
//
// `resumeFrom > 0` sends a `Range` header and appends.
// The in-flight request is registered in `activeDownloads` so calling [CancelDownload] with
// `destPath` (or [DownloadToFileWithID] with `downloadID`) cancels the context and closes
// the TCP stream immediately.
func DownloadToFile(signedURL string, destPath string, resumeFrom int64) string {
	return DownloadToFileWithID(signedURL, destPath, resumeFrom, "")
}

func DownloadToFileWithID(signedURL string, destPath string, resumeFrom int64, downloadID string) string {
	s := GetSession()
	if s == nil {
		return marshalDownloadResult(failBase(ErrCodeAuthExpired, 0, "No active session"), "", 0, "")
	}
	if strings.TrimSpace(signedURL) == "" {
		return marshalDownloadResult(failBase(ErrCodeNotFound, 0, "signed URL is empty"), "", 0, "")
	}
	if strings.TrimSpace(destPath) == "" {
		return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "destPath is empty"), "", 0, "")
	}

	downloadID = strings.TrimSpace(downloadID)
	if downloadID == "" {
		downloadID = destPath
	}

	// A cancel that arrived while the caller was still minting the signed URL is honoured here:
	// the download exits before opening the destination file or touching the network.
	activeDownloadsMu.Lock()
	preCancelled := isCancelledLocked(downloadID) || (downloadID != destPath && isCancelledLocked(destPath))
	activeDownloadsMu.Unlock()
	if preCancelled {
		return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "download cancelled"), "", 0, destPath)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	activeDownloadsMu.Lock()
	activeDownloads[downloadID] = cancel
	if downloadID != destPath {
		activeDownloads[destPath] = cancel
	}
	// Re-check under the same critical section that published the entry. A CancelDownload that
	// lands between the pre-check above and this line would otherwise see no handle and only
	// leave a marker behind, letting the transfer run to completion.
	raced := isCancelledLocked(downloadID) || (downloadID != destPath && isCancelledLocked(destPath))
	activeDownloadsMu.Unlock()

	if raced {
		cancel()
		activeDownloadsMu.Lock()
		delete(activeDownloads, downloadID)
		if downloadID != destPath {
			delete(activeDownloads, destPath)
		}
		activeDownloadsMu.Unlock()
		return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "download cancelled"), "", 0, destPath)
	}

	defer func() {
		activeDownloadsMu.Lock()
		delete(activeDownloads, downloadID)
		if downloadID != destPath {
			delete(activeDownloads, destPath)
		}
		activeDownloadsMu.Unlock()
		// Drop any marker left over from a cancel that raced with completion, so it cannot
		// abort a future attempt at the same document.
		clearCancelled(downloadID)
		if downloadID != destPath {
			clearCancelled(destPath)
		}
	}()

	flags := os.O_CREATE | os.O_WRONLY
	if resumeFrom > 0 {
		flags |= os.O_APPEND
	} else {
		flags |= os.O_TRUNC
	}

	f, err := os.OpenFile(destPath, flags, 0o644)
	if err != nil {
		return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "open dest: "+err.Error()), "", 0, "")
	}
	defer f.Close()

	// Built with `http.NewRequestWithContext`, NOT `s.NewRequest`.
	//
	// `s.NewRequest` stamps `Origin: https://www.icloud.com` and `Referer: https://www.icloud.com/`
	// onto every request it builds, which is correct for the API hosts and fatal for the CDN. The
	// signed URL already carries its own authorisation in the query string, and a request that
	// arrives with an iCloud origin is rejected with `503 Bad Gateway`.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, signedURL, nil)
	if err != nil {
		return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "build request: "+err.Error()), "", 0, "")
	}
	// No cookies and no Origin/Referer: the signature is the only credential the CDN accepts.
	if resumeFrom > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", resumeFrom))
	}

	client := &http.Client{Transport: s.Client.Transport}

	resp, err := client.Do(req)
	if err != nil {
		if ctx.Err() == context.Canceled {
			return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "download cancelled"), "", 0, destPath)
		}
		return marshalDownloadResult(failBase(ClassifyNetErr(err), 0, err.Error()), "", 0, "")
	}
	defer resp.Body.Close()

	// 200 for a fresh download, 206 when the Range was honoured. A 200 on a *resumed* request
	// means the server ignored the Range and is sending the whole file — appending it would
	// corrupt the result, so that is refused rather than silently producing a bad file.
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		preview := ""
		if b, e := io.ReadAll(io.LimitReader(resp.Body, 256)); e == nil {
			preview = string(b)
		}
		return marshalDownloadResult(
			failBase(ClassifyHTTPStatus(resp.StatusCode), resp.StatusCode,
				fmt.Sprintf("HTTP %d: %s", resp.StatusCode, preview)), "", 0, destPath)
	}
	if resumeFrom > 0 && resp.StatusCode == http.StatusOK {
		return marshalDownloadResult(
			failBase(ErrCodeUnknown, resp.StatusCode,
				"server ignored Range on a resumed download; refusing to append"), "", 0, destPath)
	}

	written, err := io.Copy(f, resp.Body)
	if err != nil {
		if ctx.Err() == context.Canceled {
			return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "download cancelled"), "", written, destPath)
		}
		return marshalDownloadResult(
			failBase(ClassifyNetErr(err), resp.StatusCode, "copy: "+err.Error()), "", written, destPath)
	}

	return marshalDownloadResult(okBase(), "", written, destPath)
}

// ---- helpers ---------------------------------------------------------------------------

func marshalDownloadToken(base ResultBase, docwsID string, token *DriveDownloadToken) string {
	b, _ := json.Marshal(DriveDownloadTokenResult{
		ResultBase: base,
		DocwsID:    docwsID,
		Token:      token,
	})
	return string(b)
}

func marshalDownloadResult(base ResultBase, docwsID string, written int64, path string) string {
	b, _ := json.Marshal(DriveDownloadResult{
		ResultBase: base,
		DocwsID:    docwsID,
		Written:    written,
		Path:       path,
	})
	return string(b)
}

// bareDocwsID strips the `FILE::zone::` prefix, since `document_id` wants only the uuid.
func bareDocwsID(id string) string {
	if i := strings.LastIndex(id, "::"); i >= 0 {
		return id[i+2:]
	}
	return id
}

// zoneForDocwsID returns the zone segment of a fully-qualified drivewsid, or the default.
//
// CloudDocs files and APP_LIBRARY containers live in different zones, and the download endpoint
// needs the right one in the path.
func zoneForDocwsID(id string) string {
	parts := strings.Split(id, "::")
	if len(parts) >= 3 {
		return parts[1]
	}
	return "com.apple.CloudDocs"
}

// docsEndpointFromAccountInfo pulls the `docws` base URL out of the stored /validate body.
func docsEndpointFromAccountInfo(raw string) string {
	if raw == "" {
		return ""
	}
	var parsed struct {
		Webservices map[string]struct {
			URL string `json:"url"`
		} `json:"webservices"`
	}
	if json.Unmarshal([]byte(raw), &parsed) != nil {
		return ""
	}
	return normalizeDrivewsURL(parsed.Webservices["docws"].URL)
}
