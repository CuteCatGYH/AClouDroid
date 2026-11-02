package icloudcore

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// DriveNodeResult is the single-node variant of DriveFolderListResult.
//
// The wrapping differs between the two endpoints and getting it wrong is not a parse error —
// the server rejects the request with a bare `400 Bad Request` (verified, REFERENCE.md §5.7):
//
//	POST /retrieveItemDetailsInFolders   body: [{...}]          (bare array)
//	POST /retrieveItemDetails            body: {"items":[{...}]} (wrapped)
//
// This struct therefore carries a single item rather than a slice; flattening the wrapper here
// keeps that asymmetry out of the Kotlin layer, which only ever wants "the node".
type DriveNodeResult struct {
	ResultBase
	DrivewsID string     `json:"drivewsid,omitempty"`
	Item      *DriveItem `json:"item,omitempty"`
}

func marshalDriveNodeResult(base ResultBase, drivewsID string, item *DriveItem) string {
	res := DriveNodeResult{
		ResultBase: base,
		DrivewsID:  drivewsID,
		Item:       item,
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// RetrieveItemDetails fetches one node's metadata without its children.
//
// This is the cheap probe the incremental sync walk is built on: the response for a folder is a
// few hundred bytes instead of the whole listing, which is what makes visiting every cached
// container per pass affordable.
//
// Default node ID (root): "FOLDER::com.apple.CloudDocs::root".
// Endpoint: POST {DrivewsURL}/retrieveItemDetails
// Body: {"items":[{"drivewsid": id, "partialData": false, "includeHierarchy": false}]}
func RetrieveItemDetails(drivewsID string) string {
	s := GetSession()
	if s == nil {
		return marshalDriveNodeResult(failBase(ErrCodeAuthExpired, 0, "No active session"), drivewsID, nil)
	}

	s.mu.RLock()
	driveURL := s.DrivewsURL
	s.mu.RUnlock()

	if driveURL == "" {
		return marshalDriveNodeResult(failBase(ErrCodeAuthExpired, 0, "DrivewsURL is not set"), drivewsID, nil)
	}

	drivewsID = strings.TrimSpace(drivewsID)
	if drivewsID == "" || drivewsID == "root" {
		drivewsID = "FOLDER::com.apple.CloudDocs::root"
	}

	endpoint := strings.TrimRight(driveURL, "/") + "/retrieveItemDetails"
	// NOTE the wrapper: this endpoint takes an object with an `items` key, unlike
	// retrieveItemDetailsInFolders which takes the array directly.
	payload := map[string]interface{}{
		"items": []map[string]interface{}{
			{
				"drivewsid":        drivewsID,
				"partialData":      false,
				"includeHierarchy": false,
			},
		},
	}

	reqBodyBytes, err := json.Marshal(payload)
	if err != nil {
		return marshalDriveNodeResult(failBase(ErrCodeParse, 0, "Failed to encode request payload: "+err.Error()), drivewsID, nil)
	}

	req, err := s.NewRequest(http.MethodPost, endpoint, string(reqBodyBytes))
	if err != nil {
		return marshalDriveNodeResult(failBase(ClassifyNetErr(err), 0, err.Error()), drivewsID, nil)
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return marshalDriveNodeResult(failBase(ClassifyNetErr(err), 0, err.Error()), drivewsID, nil)
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return marshalDriveNodeResult(failBase(ErrCodeNetwork, resp.StatusCode, "Failed to read response body: "+err.Error()), drivewsID, nil)
	}

	if resp.StatusCode != http.StatusOK {
		errCode := ClassifyHTTPStatus(resp.StatusCode)
		preview := string(bodyBytes)
		if len(preview) > 256 {
			preview = preview[:256] + "..."
		}
		return marshalDriveNodeResult(failBase(errCode, resp.StatusCode, fmt.Sprintf("HTTP %d: %s", resp.StatusCode, preview)), drivewsID, nil)
	}

	var item *DriveItem
	trimmed := bytes.TrimSpace(bodyBytes)
	if len(trimmed) > 0 {
		var wrapper struct {
			Items []DriveItem `json:"items"`
		}
		if err := json.Unmarshal(trimmed, &wrapper); err != nil {
			return marshalDriveNodeResult(failBase(ErrCodeParse, resp.StatusCode, "Failed to parse node response: "+err.Error()), drivewsID, nil)
		}
		if len(wrapper.Items) == 0 {
			// An empty `items` is a well-formed "I have nothing for that id", which is exactly
			// what a deleted folder returns. Surfacing NOT_FOUND lets the caller drop the row
			// instead of treating it as a transient failure and retrying forever.
			return marshalDriveNodeResult(failBase(ErrCodeNotFound, resp.StatusCode, "Node not found: "+drivewsID), drivewsID, nil)
		}
		item = &wrapper.Items[0]
	}

	return marshalDriveNodeResult(okBase(), drivewsID, item)
}
