package icloudcore

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type DriveOpResult struct {
	ResultBase
	Items []DriveItem `json:"items,omitempty"`
}

func makeDriveOpError(code string, httpStatus int, msg string) string {
	res := DriveOpResult{
		ResultBase: failBase(code, httpStatus, msg),
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// CreateDriveFolder creates a new folder under destinationDrivewsID in iCloud Drive.
//
// Endpoint: POST {DrivewsURL}/createFolders
// Payload: {"destinationDrivewsId": "...", "folders": [{"name": "...", "clientId": "...", "type": "FOLDER"}]}
func CreateDriveFolder(destinationDrivewsID string, folderName string) string {
	destinationDrivewsID = strings.TrimSpace(destinationDrivewsID)
	if destinationDrivewsID == "" {
		destinationDrivewsID = "FOLDER::com.apple.CloudDocs::root"
	}
	folderName = strings.TrimSpace(folderName)
	if folderName == "" {
		return makeDriveOpError(ErrCodeParse, 0, "Folder name cannot be empty")
	}

	s := GetSession()
	if s == nil {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "No active session in Go core")
	}

	s.mu.RLock()
	drivewsURL := s.DrivewsURL
	s.mu.RUnlock()

	if drivewsURL == "" {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "DrivewsURL is not set")
	}

	url := fmt.Sprintf("%s/createFolders", strings.TrimRight(drivewsURL, "/"))
	clientID := fmt.Sprintf("create-folder-%d", time.Now().UnixNano())
	payload := map[string]interface{}{
		"destinationDrivewsId": destinationDrivewsID,
		"folders": []map[string]interface{}{
			{
				"name":     folderName,
				"clientId": clientID,
				"type":     "FOLDER",
			},
		},
	}

	reqBody, err := json.Marshal(payload)
	if err != nil {
		return makeDriveOpError(ErrCodeParse, 0, "Failed to encode payload: "+err.Error())
	}

	req, err := s.NewRequest(http.MethodPost, url, string(reqBody))
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Failed to build request: "+err.Error())
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Request execution failed: "+err.Error())
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return makeDriveOpError(ErrCodeParse, resp.StatusCode, "Failed to read response body: "+err.Error())
	}

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == 421 {
		return makeDriveOpError(ErrCodeAuthExpired, resp.StatusCode, "Authentication expired")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return makeDriveOpError(ClassifyHTTPStatus(resp.StatusCode), resp.StatusCode, fmt.Sprintf("Server returned status %d: %s (req: %s)", resp.StatusCode, string(bodyBytes), string(reqBody)))
	}

	// Parse response
	var raw struct {
		Folders []DriveItem `json:"folders"`
		Items   []DriveItem `json:"items"`
	}
	if err := json.Unmarshal(bodyBytes, &raw); err != nil {
		return makeDriveOpError(ErrCodeParse, resp.StatusCode, "Failed to parse JSON response: "+err.Error())
	}

	items := raw.Folders
	if len(items) == 0 {
		items = raw.Items
	}

	res := DriveOpResult{
		ResultBase: okBase(),
		Items:      items,
	}
	resBytes, _ := json.Marshal(res)
	return string(resBytes)
}

// RenameDriveItem renames a file or folder by drivewsID and etag.
//
// Endpoint: POST {DrivewsURL}/renameItems
// Payload: {"items": [{"drivewsid": "...", "etag": "...", "name": "..."}]}
func RenameDriveItem(drivewsid string, etag string, newName string) string {
	drivewsid = strings.TrimSpace(drivewsid)
	if drivewsid == "" {
		return makeDriveOpError(ErrCodeParse, 0, "drivewsid cannot be empty")
	}
	newName = strings.TrimSpace(newName)
	if newName == "" {
		return makeDriveOpError(ErrCodeParse, 0, "New name cannot be empty")
	}

	s := GetSession()
	if s == nil {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "No active session in Go core")
	}

	s.mu.RLock()
	drivewsURL := s.DrivewsURL
	s.mu.RUnlock()

	if drivewsURL == "" {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "DrivewsURL is not set")
	}

	url := fmt.Sprintf("%s/renameItems", strings.TrimRight(drivewsURL, "/"))
	payload := map[string]interface{}{
		"items": []map[string]string{
			{
				"drivewsid": drivewsid,
				"etag":      etag,
				"name":      newName,
			},
		},
	}

	reqBody, _ := json.Marshal(payload)
	req, err := s.NewRequest(http.MethodPost, url, string(reqBody))
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Failed to build request: "+err.Error())
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Request execution failed: "+err.Error())
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return makeDriveOpError(ErrCodeParse, resp.StatusCode, "Failed to read response body: "+err.Error())
	}

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == 421 {
		return makeDriveOpError(ErrCodeAuthExpired, resp.StatusCode, "Authentication expired")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return makeDriveOpError(ClassifyHTTPStatus(resp.StatusCode), resp.StatusCode, fmt.Sprintf("Server returned status %d: %s (req: %s)", resp.StatusCode, string(bodyBytes), string(reqBody)))
	}

	var raw struct {
		Items []DriveItem `json:"items"`
	}
	_ = json.Unmarshal(bodyBytes, &raw)

	res := DriveOpResult{
		ResultBase: okBase(),
		Items:      raw.Items,
	}
	resBytes, _ := json.Marshal(res)
	return string(resBytes)
}

// MoveDriveItems moves one or more files/folders to a destination folder.
//
// Endpoint: POST {DrivewsURL}/moveItems
// itemsJson: JSON array of items, e.g. [{"drivewsid": "...", "etag": "...", "clientId": "..."}]
func MoveDriveItems(destinationDrivewsID string, itemsJson string) string {
	destinationDrivewsID = strings.TrimSpace(destinationDrivewsID)
	if destinationDrivewsID == "" {
		return makeDriveOpError(ErrCodeParse, 0, "Destination folder ID cannot be empty")
	}

	var items []map[string]interface{}
	if err := json.Unmarshal([]byte(itemsJson), &items); err != nil {
		return makeDriveOpError(ErrCodeParse, 0, "Failed to parse items JSON: "+err.Error())
	}
	if len(items) == 0 {
		return makeDriveOpError(ErrCodeParse, 0, "Items list is empty")
	}

	for _, item := range items {
		if _, hasClient := item["clientId"]; !hasClient {
			if id, ok := item["drivewsid"].(string); ok {
				item["clientId"] = id
			}
		}
	}

	s := GetSession()
	if s == nil {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "No active session in Go core")
	}

	s.mu.RLock()
	drivewsURL := s.DrivewsURL
	s.mu.RUnlock()

	if drivewsURL == "" {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "DrivewsURL is not set")
	}

	url := fmt.Sprintf("%s/moveItems", strings.TrimRight(drivewsURL, "/"))
	payload := map[string]interface{}{
		"destinationDrivewsId": destinationDrivewsID,
		"items":                items,
	}

	reqBody, _ := json.Marshal(payload)
	req, err := s.NewRequest(http.MethodPost, url, string(reqBody))
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Failed to build request: "+err.Error())
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Request execution failed: "+err.Error())
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return makeDriveOpError(ErrCodeParse, resp.StatusCode, "Failed to read response body: "+err.Error())
	}

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == 421 {
		return makeDriveOpError(ErrCodeAuthExpired, resp.StatusCode, "Authentication expired")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return makeDriveOpError(ClassifyHTTPStatus(resp.StatusCode), resp.StatusCode, fmt.Sprintf("Server returned status %d: %s (req: %s)", resp.StatusCode, string(bodyBytes), string(reqBody)))
	}

	var raw struct {
		Items []DriveItem `json:"items"`
	}
	_ = json.Unmarshal(bodyBytes, &raw)

	res := DriveOpResult{
		ResultBase: okBase(),
		Items:      raw.Items,
	}
	resBytes, _ := json.Marshal(res)
	return string(resBytes)
}

// MoveDriveItemsToTrash moves one or more files/folders to Trash.
//
// Endpoint: POST {DrivewsURL}/moveItemsToTrash
// itemsJson: JSON array of items, e.g. [{"drivewsid": "...", "etag": "...", "clientId": "..."}]
func MoveDriveItemsToTrash(itemsJson string) string {
	var items []map[string]interface{}
	if err := json.Unmarshal([]byte(itemsJson), &items); err != nil {
		return makeDriveOpError(ErrCodeParse, 0, "Failed to parse items JSON: "+err.Error())
	}
	if len(items) == 0 {
		return makeDriveOpError(ErrCodeParse, 0, "Items list is empty")
	}

	for _, item := range items {
		if _, hasClient := item["clientId"]; !hasClient {
			if id, ok := item["drivewsid"].(string); ok {
				item["clientId"] = id
			}
		}
	}

	s := GetSession()
	if s == nil {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "No active session in Go core")
	}

	s.mu.RLock()
	drivewsURL := s.DrivewsURL
	s.mu.RUnlock()

	if drivewsURL == "" {
		return makeDriveOpError(ErrCodeAuthExpired, 0, "DrivewsURL is not set")
	}

	url := fmt.Sprintf("%s/moveItemsToTrash", strings.TrimRight(drivewsURL, "/"))
	payload := map[string]interface{}{
		"items": items,
	}

	reqBody, _ := json.Marshal(payload)
	req, err := s.NewRequest(http.MethodPost, url, string(reqBody))
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Failed to build request: "+err.Error())
	}

	resp, err := s.Client.Do(req)
	if err != nil {
		return makeDriveOpError(ClassifyNetErr(err), 0, "Request execution failed: "+err.Error())
	}
	defer resp.Body.Close()

	s.ExtractAndSaveCookies(resp)

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return makeDriveOpError(ErrCodeParse, resp.StatusCode, "Failed to read response body: "+err.Error())
	}

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == 421 {
		return makeDriveOpError(ErrCodeAuthExpired, resp.StatusCode, "Authentication expired")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return makeDriveOpError(ClassifyHTTPStatus(resp.StatusCode), resp.StatusCode, fmt.Sprintf("Server returned status %d: %s (req: %s)", resp.StatusCode, string(bodyBytes), string(reqBody)))
	}

	var raw struct {
		Items []DriveItem `json:"items"`
	}
	_ = json.Unmarshal(bodyBytes, &raw)

	res := DriveOpResult{
		ResultBase: okBase(),
		Items:      raw.Items,
	}
	resBytes, _ := json.Marshal(res)
	return string(resBytes)
}
