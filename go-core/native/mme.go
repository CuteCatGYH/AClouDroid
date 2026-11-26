package native

// MobileMe: trade the GSA PET for mmeAuthToken, and mmeAuthToken for cloudKitToken.
//
// Measured on 2026-11-25 (probes/native_ck step 1): v1 loginDelegates returns 200 with no
// token, v2 signin/login returns 400 (it needs Apple's NAC signature), and only the legacy
// GET setup/authenticate/<appleid> returns mmeAuthToken. v1 is still tried first so a future
// server change shows up as a different winner; v2 is not tried because it cannot succeed
// without the NAC signature.

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"time"

	"howett.net/plist"
)

type mmeTokens struct {
	DSID   string
	Tokens map[string]string
}

func setupHeaders(req *http.Request, ani Anisette) {
	for k, v := range ani {
		if k == "X-Mme-Client-Info" || k == "X-Apple-SRL-NO" {
			continue
		}
		req.Header.Set(k, v)
	}
	req.Header.Set("Content-Type", "text/plist")
	req.Header.Set("Accept-Encoding", "gzip")
	req.Header.Set("User-Agent", "com.apple.iCloudHelper/282 CFNetwork/1494.0.7 Darwin/23.4.0")
	req.Header.Set("X-Mme-Client-Info", clientInfoFor(ani, "<com.apple.AOSKit/282 (com.apple.accountsd/113)>"))
}

func doPlist(req *http.Request) (map[string]interface{}, int, error) {
	resp, err := httpClient(60 * time.Second).Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	raw = maybeGunzip(raw)
	var out map[string]interface{}
	if _, err := plist.Unmarshal(raw, &out); err != nil {
		return nil, resp.StatusCode, fmt.Errorf("reply not a plist (HTTP %d)", resp.StatusCode)
	}
	return out, resp.StatusCode, nil
}

func extractMobileMe(out map[string]interface{}) (*mmeTokens, string) {
	if e, ok := out["localizedError"].(string); ok {
		d, _ := out["description"].(string)
		return nil, e + " " + d
	}
	if dels, ok := out["delegates"].(map[string]interface{}); ok {
		mm, _ := dels["com.apple.mobileme"].(map[string]interface{})
		if mm == nil {
			return nil, "no com.apple.mobileme delegate"
		}
		sd, _ := mm["service-data"].(map[string]interface{})
		if sd == nil {
			return nil, fmt.Sprintf("mobileme status=%v", mm["status"])
		}
		out = sd
	}
	t := &mmeTokens{Tokens: map[string]string{}}
	if toks, ok := out["tokens"].(map[string]interface{}); ok {
		for k, v := range toks {
			if s, ok := v.(string); ok {
				t.Tokens[k] = s
			}
		}
	}
	if info, ok := out["appleAccountInfo"].(map[string]interface{}); ok {
		if v, ok := info["dsPrsID"]; ok {
			t.DSID = fmt.Sprint(v)
		} else if v, ok := info["dsid"]; ok {
			t.DSID = fmt.Sprint(v)
		}
	}
	if len(t.Tokens) == 0 {
		return nil, fmt.Sprintf("no tokens (status=%v)", out["status"])
	}
	return t, ""
}

func mobileMeLogin(ani Anisette, s *gsaSession) (*mmeTokens, error) {
	var reasons []string
	auth := func(req *http.Request) {
		setupHeaders(req, ani)
		req.Header.Set("X-Apple-ADSID", s.ADSID)
		req.SetBasicAuth(s.Username, s.PET)
	}
	try := func(label string, req *http.Request) *mmeTokens {
		auth(req)
		out, status, err := doPlist(req)
		if err != nil {
			reasons = append(reasons, label+": "+err.Error())
			return nil
		}
		t, why := extractMobileMe(out)
		if t == nil {
			reasons = append(reasons, fmt.Sprintf("%s: HTTP %d %s", label, status, why))
			return nil
		}
		if t.Tokens["mmeAuthToken"] == "" {
			reasons = append(reasons, label+": no mmeAuthToken")
			return nil
		}
		logf("MobileMe ok via %s", label)
		return t
	}

	body, _ := plist.MarshalIndent(map[string]interface{}{
		"apple-id":  s.Username,
		"client-id": NewUUID(),
		"delegates": map[string]interface{}{"com.apple.mobileme": map[string]interface{}{}},
		"password":  s.PET,
	}, plist.XMLFormat, "\t")
	req, _ := http.NewRequest("POST", "https://setup.icloud.com/setup/iosbuddy/loginDelegates", bytes.NewReader(body))
	if t := try("v1 loginDelegates", req); t != nil {
		return t, nil
	}

	req, _ = http.NewRequest("GET", "https://setup.icloud.com/setup/authenticate/"+s.Username, nil)
	if t := try("legacy setup/authenticate", req); t != nil {
		return t, nil
	}
	return nil, fmt.Errorf("MobileMe login failed: %v", reasons)
}

// accountSettings trades dsid:mmeAuthToken for the full token set; cloudKitToken lives there.
func accountSettings(ani Anisette, dsid, mmeToken string) (*mmeTokens, int, error) {
	req, _ := http.NewRequest("POST", "https://setup.icloud.com/setup/get_account_settings", nil)
	setupHeaders(req, ani)
	req.SetBasicAuth(dsid, mmeToken)
	out, status, err := doPlist(req)
	if err != nil {
		return nil, status, err
	}
	t, why := extractMobileMe(out)
	if t == nil {
		return nil, status, fmt.Errorf("get_account_settings HTTP %d: %s", status, why)
	}
	if t.DSID == "" {
		t.DSID = dsid
	}
	return t, status, nil
}
