package icloudcore

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
)

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
// ## Why this lives in Go rather than Kotlin
//
// The alternative is `URL.openStream().copyTo(file)` on the Kotlin side, which is fine — but the
// project already owns the HTTP client that holds the session cookies here, and a Go-side
// downloader keeps the "one place talks to the network" rule intact (AGENTS §10).
//
// ## Why a path and not a byte array
//
// Files here reach 300 MB+. Crossing the JNI boundary with a `[]byte` would allocate the whole
// file twice (PITFALLS §4). The file descriptor never leaves Go.
//
// ## Resumption
//
// `resumeFrom > 0` sends a `Range` header and appends. The caller is responsible for having
// verified that the partial file on disk is genuinely a prefix of this resource — this function
// cannot tell a partial download from a corrupt one.
func DownloadToFile(signedURL string, destPath string, resumeFrom int64) string {
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

	// Built with `http.NewRequest`, NOT `s.NewRequest`.
	//
	// `s.NewRequest` stamps `Origin: https://www.icloud.com` and `Referer: https://www.icloud.com/`
	// onto every request it builds, which is correct for the API hosts and fatal for the CDN. The
	// signed URL already carries its own authorisation in the query string, and a request that
	// arrives with an iCloud origin is rejected with `503 Bad Gateway`.
	//
	// Verified on a real account: identical URL, bare request -> 200 + real HEIC bytes; the same
	// URL through `s.NewRequest` -> 503. Do not "simplify" this back.
	req, err := http.NewRequest(http.MethodGet, signedURL, nil)
	if err != nil {
		return marshalDownloadResult(failBase(ErrCodeUnknown, 0, "build request: "+err.Error()), "", 0, "")
	}
	// No cookies and no Origin/Referer: the signature is the only credential the CDN accepts.
	if resumeFrom > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", resumeFrom))
	}

	// A DEDICATED client with no cookie jar. This is the whole reason the first attempt returned
	// `503 Bad Gateway`:
	//
	// `s.Client` owns the session's `cookiejar`, so reusing it here silently attached the iCloud
	// auth cookies to the CDN request. The CDN rejects a signed URL that arrives with session
	// cookies — the signature is the only credential it accepts. A probe run with a bare
	// `&http.Client{}` against the same URL returned 200 and real bytes, which is how this was
	// found (REFERENCE.md §5.11).
	//
	// Do not "simplify" this back to `s.Client`: sharing the transport was fine, sharing the jar
	// was not, and `http.Client` ties the two together.
	//
	// No timeout either — a 300 MB file over a slow link is normal, and a truncated stream is
	// worse than a slow one. Callers that need a bound pass their own via cancellation.
	client := &http.Client{Transport: s.Client.Transport}

	resp, err := client.Do(req)
	if err != nil {
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
		// Report what did land: the caller keeps the partial file and resumes from this offset.
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
