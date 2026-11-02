package icloudcore

// TEMPORARY PROBE — D3 of the iCloud Drive phase (docs/TODO_DRIVE.md §5.0).
//
// D0 established that `/ws/com.apple.CloudDocs/changes/database` does not exist
// (`WSModuleNotFoundException`). That kills the planned "two-way delta" design, so D3 needs to
// find the *real* structure-delta mechanism before it can be implemented.
//
// This probe answers, with live traffic, which of the candidate approaches exist:
//
//	1. `changes/zone`            — did the endpoint merely get renamed?
//	2. `records/changes`         — is there a CloudKit-level delta for the CloudDocs zone?
//	3. `changes/database` variants (GET vs POST, different bodies) — is D0's answer shape-dependent?
//	4. `retrieveItemDetails`     — the non-`InFolders` variant; does it carry a token/etag?
//	5. `/zones/list` on ckdatabasews — which zones does this account actually have?
//	6. `etag` behaviour on a re-listing — is polling `etag` a viable fallback?
//
// Everything is written to a caller-supplied directory (cacheDir) AND returned as a summary, so
// the findings survive the app being uninstalled. Deleted once archived into REFERENCE.md §5.8.

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type probeStep struct {
	Step   string `json:"step"`
	Method string `json:"method"`
	URL    string `json:"url"`
	Status int    `json:"status"`
	OK     bool   `json:"ok"`
	Bytes  int    `json:"bytes"`
	Note   string `json:"note,omitempty"`
	Head   string `json:"head,omitempty"`
}

// ProbeDriveDeltaCandidates runs the D3 reconnaissance sequence and writes one file per step.
func ProbeDriveDeltaCandidates(outDir string) string {
	s := GetSession()
	if s == nil {
		return `{"success":false,"errorCode":"AUTH_EXPIRED","errorMessage":"no active session"}`
	}
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return fmt.Sprintf(`{"success":false,"errorCode":"PARSE","errorMessage":%q}`, err.Error())
	}

	var steps []probeStep

	rec := func(step, method, url string, status int, body []byte, note string) []byte {
		st := probeStep{
			Step:   step,
			Method: method,
			URL:    scrubURL(url),
			Status: status,
			OK:     status == http.StatusOK,
			Bytes:  len(body),
			Note:   note,
		}
		if len(body) > 0 {
			head := body
			if len(head) > 900 {
				head = head[:900]
			}
			st.Head = string(head)
		}
		if name := sanitize(step) + ".json"; name != "" {
			_ = os.WriteFile(filepath.Join(outDir, name), body, 0o600)
		}
		steps = append(steps, st)
		return body
	}

	docws := s.docsEndpointForProbe()
	drivews := s.drivewsForProbe()
	dsid := s.dsidForProbeD3()

	// ---------------------------------------------------------------- 1. changes/zone
	// The most likely rename. Tried both as POST-with-token and as a bare POST, because the
	// CloudKit-style endpoints in this API generally take a JSON body.
	if docws != "" {
		body := []byte(`{"syncToken":""}`)
		url := docws + "/ws/com.apple.CloudDocs/changes/zone"
		st, b := s.probeDo(http.MethodPost, url, string(body))
		rec("1a_changes_zone_post_empty", "POST", url, st, b, "rename candidate with empty token")

		body2 := []byte(`{"zones":[{"zoneName":"com.apple.CloudDocs","syncToken":""}]}`)
		st, b = s.probeDo(http.MethodPost, url, string(body2))
		rec("1b_changes_zone_post_zones", "POST", url, st, b, "CloudKit-shaped zones body")

		st, b = s.probeDo(http.MethodGet, url, "")
		rec("1c_changes_zone_get", "GET", url, st, b, "GET variant")

		// CloudKit's real path shape.
		url2 := docws + "/database/1/com.apple.CloudDocs/production/private/changes/zone"
		st, b = s.probeDo(http.MethodPost, url2, `{"zones":[{"zoneName":"com.apple.CloudDocs","syncToken":""}]}`)
		rec("1d_changes_zone_cloudkit_path", "POST", url2, st, b, "CloudKit /database/1/.../changes/zone")
	}

	// ---------------------------------------------------------------- 2. records/changes
	// If CloudDocs is a CloudKit zone, the Photos delta endpoint may work verbatim.
	if docws != "" {
		url := docws + "/database/1/com.apple.CloudDocs/production/private/records/changes"
		st, b := s.probeDo(http.MethodPost, url, `{"zoneID":{"zoneName":"com.apple.CloudDocs"},"resultsLimit":20}`)
		rec("2a_records_changes_clouddocs", "POST", url, st, b, "CloudKit records/changes on CloudDocs zone")

		url2 := docws + "/database/1/com.apple.CloudDocs/production/private/zones/list"
		st, b = s.probeDo(http.MethodPost, url2, `{}`)
		rec("2b_zones_list_clouddocs", "POST", url2, st, b, "which zones exist on docws")
	}

	// ---------------------------------------------------------------- 3. changes/database variants
	// D0 only tried one shape. Rule it out properly before abandoning it.
	if docws != "" {
		url := docws + "/ws/com.apple.CloudDocs/changes/database"
		st, b := s.probeDo(http.MethodGet, url, "")
		rec("3a_changes_database_get", "GET", url, st, b, "GET variant of the D0 404")

		st, b = s.probeDo(http.MethodPost, url, `{"dsid":"`+dsid+`"}`)
		rec("3b_changes_database_post_dsid", "POST", url, st, b, "POST with dsid")
	}

	// ---------------------------------------------------------------- 4. retrieveItemDetails
	// The non-InFolders variant returns the node *without* children. If it carries an etag or a
	// change tag per node, polling it is cheaper than re-listing whole folders.
	if drivews != "" {
		body := `[{"drivewsid":"FOLDER::com.apple.CloudDocs::root","partialData":false,"includeHierarchy":false}]`
		url := drivews + "/retrieveItemDetails"
		st, b := s.probeDo(http.MethodPost, url, body)
		rec("4a_retrieve_item_details", "POST", url, st, b, "node metadata without children")
	}

	// ---------------------------------------------------------------- 5. root etag stability
	// Candidate D: poll the root node's `etag` / `directChildrenCount`. Two listings back to back
	// must agree; the probe records both so the field's semantics can be judged from real values.
	if drivews != "" {
		url := drivews + "/retrieveItemDetailsInFolders"
		body := `[{"drivewsid":"FOLDER::com.apple.CloudDocs::root","partialData":false,"includeHierarchy":false}]`
		_, b1 := s.probeDo(http.MethodPost, url, body)
		time.Sleep(300 * time.Millisecond)
		_, b2 := s.probeDo(http.MethodPost, url, body)

		rec("5a_root_etag_pass1", "POST", url, 200, b1, "first listing: "+rootEtagSummary(b1))
		rec("5b_root_etag_pass2", "POST", url, 200, b2, "second listing: "+rootEtagSummary(b2))
		if len(b1) > 0 && len(b2) > 0 {
			steps = append(steps, probeStep{
				Step: "5c_root_listing_stable",
				OK:   rootEtag(b1) == rootEtag(b2),
				Note: fmt.Sprintf("etag %q vs %q", rootEtag(b1), rootEtag(b2)),
			})
		}
	}

	out := map[string]interface{}{
		"success":    true,
		"outDir":     outDir,
		"docws":      docws,
		"drivews":    drivews,
		"steps":      steps,
		"finishedAt": time.Now().Format(time.RFC3339),
	}
	enc, _ := json.MarshalIndent(out, "", "  ")
	return string(enc)
}

// ---- helpers ---------------------------------------------------------------------------

func (s *Session) drivewsForProbe() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.DrivewsURL
}

func (s *Session) dsidForProbeD3() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.DSID
}

// docsEndpointForProbe reads `docws` out of the stored /validate body. Kept here rather than added
// to Session because D3 may or may not end up needing it permanently.
func (s *Session) docsEndpointForProbe() string {
	s.mu.RLock()
	raw := s.AccountInfoJSON
	s.mu.RUnlock()
	if raw == "" {
		return ""
	}
	var parsed struct {
		Webservices map[string]struct {
			URL string `json:"url"`
		} `json:"webservices"`
	}
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return ""
	}
	return normalizeDrivewsURL(parsed.Webservices["docws"].URL)
}

func (s *Session) probeDo(method, url, payload string) (int, []byte) {
	req, err := s.NewRequest(method, url, payload)
	if err != nil {
		return 0, []byte(fmt.Sprintf(`{"error":%q}`, err.Error()))
	}
	if method == http.MethodGet {
		req.Body = http.NoBody
		req.ContentLength = 0
	}
	resp, err := s.Client.Do(req)
	if err != nil {
		return 0, []byte(fmt.Sprintf(`{"error":%q}`, err.Error()))
	}
	defer resp.Body.Close()
	s.ExtractAndSaveCookies(resp)
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b
}

// rootEtag pulls the root node's etag out of a `retrieveItemDetailsInFolders` payload.
func rootEtag(body []byte) string {
	var nodes []struct {
		Etag string `json:"etag"`
	}
	if err := json.Unmarshal(body, &nodes); err != nil || len(nodes) == 0 {
		return ""
	}
	return nodes[0].Etag
}

// rootEtagSummary renders the fields that a polling-based delta would have to rely on.
func rootEtagSummary(body []byte) string {
	var nodes []struct {
		Etag                string `json:"etag"`
		DirectChildrenCount int    `json:"directChildrenCount"`
		FileCount           int    `json:"fileCount"`
	}
	if err := json.Unmarshal(body, &nodes); err != nil || len(nodes) == 0 {
		return "unparseable"
	}
	n := nodes[0]
	return fmt.Sprintf("etag=%q children=%d files=%d", n.Etag, n.DirectChildrenCount, n.FileCount)
}

// scrubURL drops the query string so no signature ever reaches a log or a file.
func scrubURL(raw string) string {
	if i := strings.IndexByte(raw, '?'); i >= 0 {
		return raw[:i] + "?<redacted>"
	}
	return raw
}

func sanitize(name string) string {
	var b strings.Builder
	for _, r := range name {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' {
			b.WriteRune(r)
		} else if r >= 'A' && r <= 'Z' {
			b.WriteRune(r + 32)
		} else {
			b.WriteRune('_')
		}
	}
	return b.String()
}
