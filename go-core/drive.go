package icloudcore

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

type DriveIcon struct {
	URL  string `json:"url,omitempty"`
	Type string `json:"type,omitempty"`
	Size int    `json:"size,omitempty"`
}

type DriveItem struct {
	DrivewsID           string      `json:"drivewsid"`
	DocwsID             string      `json:"docwsid"`
	ParentID            string      `json:"parentId"`
	Name                string      `json:"name"`
	Type                string      `json:"type"` // FOLDER, FILE, APP_LIBRARY
	Zone                string      `json:"zone"`
	Size                int64       `json:"size,omitempty"`
	DateModified        string      `json:"dateModified,omitempty"`
	DateChanged         string      `json:"dateChanged,omitempty"`
	DateCreated         string      `json:"dateCreated,omitempty"`
	Etag                string      `json:"etag,omitempty"`
	Extension           string      `json:"extension,omitempty"`
	DirectChildrenCount int         `json:"directChildrenCount,omitempty"`
	ItemID              string      `json:"item_id,omitempty"`
	Icons               []DriveIcon `json:"icons,omitempty"`
	SupportedTypes      []string    `json:"supportedTypes,omitempty"`
	SupportedExtensions []string    `json:"supportedExtensions,omitempty"`
}

type DriveFolderListResult struct {
	ResultBase
	DrivewsID string      `json:"drivewsid,omitempty"`
	Items     []DriveItem `json:"items"`
}

func marshalDriveFolderListResult(base ResultBase, drivewsID string, items []DriveItem) string {
	if items == nil {
		items = []DriveItem{}
	}
	res := DriveFolderListResult{
		ResultBase: base,
		DrivewsID:  drivewsID,
		Items:      items,
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// RetrieveItemDetailsInFolders lists the contents of a Drive folder by drivewsID.
//
// Default parent ID (root): "FOLDER::com.apple.CloudDocs::root".
// Endpoint: POST {DrivewsURL}/retrieveItemDetailsInFolders
// Body: [{"drivewsid": parentDrivewsID, "partialData": false, "includeHierarchy": false}]
func RetrieveItemDetailsInFolders(parentDrivewsID string) string {
	s := GetSession()
	if s == nil {
		return marshalDriveFolderListResult(failBase(ErrCodeAuthExpired, 0, "No active session"), parentDrivewsID, nil)
	}

	s.mu.RLock()
	driveURL := s.DrivewsURL
	s.mu.RUnlock()

	if driveURL == "" {
		return marshalDriveFolderListResult(failBase(ErrCodeAuthExpired, 0, "DrivewsURL is not set"), parentDrivewsID, nil)
	}

	parentDrivewsID = strings.TrimSpace(parentDrivewsID)
	if parentDrivewsID == "" || parentDrivewsID == "root" {
		parentDrivewsID = "FOLDER::com.apple.CloudDocs::root"
	}

	endpoint := strings.TrimRight(driveURL, "/") + "/retrieveItemDetailsInFolders"
	payload := []map[string]interface{}{
		{
			"drivewsid":        parentDrivewsID,
			"partialData":      false,
			"includeHierarchy": false,
		},
	}

	reqBodyBytes, err := json.Marshal(payload)
	if err != nil {
		return marshalDriveFolderListResult(failBase(ErrCodeParse, 0, "Failed to encode request payload: "+err.Error()), parentDrivewsID, nil)
	}

	req, err := s.NewRequest(http.MethodPost, endpoint, string(reqBodyBytes))
	if err != nil {
		return marshalDriveFolderListResult(failBase(ClassifyNetErr(err), 0, err.Error()), parentDrivewsID, nil)
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return marshalDriveFolderListResult(failBase(ClassifyNetErr(err), 0, err.Error()), parentDrivewsID, nil)
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return marshalDriveFolderListResult(failBase(ErrCodeNetwork, resp.StatusCode, "Failed to read response body: "+err.Error()), parentDrivewsID, nil)
	}

	if resp.StatusCode != http.StatusOK {
		errCode := ClassifyHTTPStatus(resp.StatusCode)
		preview := string(bodyBytes)
		if len(preview) > 256 {
			preview = preview[:256] + "..."
		}
		return marshalDriveFolderListResult(failBase(errCode, resp.StatusCode, fmt.Sprintf("HTTP %d: %s", resp.StatusCode, preview)), parentDrivewsID, nil)
	}

	var items []DriveItem
	trimmed := bytes.TrimSpace(bodyBytes)
	if len(trimmed) > 0 {
		if trimmed[0] == '[' {
			var nodes []struct {
				DrivewsID string      `json:"drivewsid"`
				Items     []DriveItem `json:"items"`
			}
			if err := json.Unmarshal(trimmed, &nodes); err == nil {
				for _, n := range nodes {
					items = append(items, n.Items...)
				}
			} else {
				return marshalDriveFolderListResult(failBase(ErrCodeParse, resp.StatusCode, "Failed to parse folder array: "+err.Error()), parentDrivewsID, nil)
			}
		} else {
			var node struct {
				DrivewsID string      `json:"drivewsid"`
				Items     []DriveItem `json:"items"`
			}
			if err := json.Unmarshal(trimmed, &node); err == nil {
				items = node.Items
			} else {
				return marshalDriveFolderListResult(failBase(ErrCodeParse, resp.StatusCode, "Failed to parse folder object: "+err.Error()), parentDrivewsID, nil)
			}
		}
	}

	return marshalDriveFolderListResult(okBase(), parentDrivewsID, items)
}
