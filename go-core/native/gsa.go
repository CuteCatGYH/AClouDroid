package native

// GrandSlam (gsa.apple.com) login: SRP init/complete, two-factor, PET extraction.
//
// The password never leaves this process in clear: SRP proves knowledge of it. It IS held in
// memory between Begin and the 2FA step (Apple wants a second SRP round after the code is
// accepted), and dropped as soon as the flow finishes or is abandoned.

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"howett.net/plist"
)

const gsaEndpoint = "https://gsa.apple.com/grandslam/GsService2"

type gsaClient struct {
	http       *http.Client
	ani        Anisette
	akdUA      string
	clientInfo string
}

func newGSAClient(ani Anisette) *gsaClient {
	return &gsaClient{
		http:       httpClient(60 * time.Second),
		ani:        ani,
		akdUA:      "akd/1.0 CFNetwork/1494.0.7 Darwin/23.4.0",
		clientInfo: clientInfoFor(ani, "<com.apple.AuthKit/1 (com.apple.akd/1.0)>"),
	}
}

func (g *gsaClient) cpd(requestID string) map[string]interface{} {
	m := map[string]interface{}{}
	for _, k := range []string{"X-Apple-I-Client-Time", "X-Apple-I-MD", "X-Apple-I-MD-LU", "X-Apple-I-MD-M", "X-Apple-I-MD-RINFO", "X-Mme-Device-Id"} {
		if v := g.ani[k]; v != "" {
			m[k] = v
		}
	}
	for k, v := range map[string]string{
		"X-Apple-I-Device-Configuration-Mode": "0",
		"X-Apple-I-Request-UUID":              requestID,
		"X-Apple-Requested-Partition":         "0",
		"X-Apple-Security-Upgrade-Context":    "com.apple.authkit.generic",
		"capp":                                "Messages",
		"cbid":                                "com.apple.MobileSMS",
		"cou":                                 "US",
		"loc":                                 "en_US",
		"svct":                                "imessage",
	} {
		m[k] = v
	}
	for k, v := range map[string]interface{}{
		"X-Apple-Offer-Security-Upgrade": true,
		"at":                             0,
		"bootstrap":                      true,
		"ckgen":                          true,
		"fcd":                            true,
		"icdrsDisabled":                  false,
		"icscrec":                        true,
		"pbe":                            false,
		"prkgen":                         true,
		"webAccessEnabled":               false,
	} {
		m[k] = v
	}
	return m
}

func (g *gsaClient) serviceHeaders(req *http.Request) {
	for k, v := range g.ani.pick(mdKeys...) {
		req.Header.Set(k, v)
	}
	req.Header.Set("Content-Type", "text/x-xml-plist")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("X-Apple-AK-Context-Type", "imessage")
	req.Header.Set("X-Apple-Client-App-Name", "Messages")
	req.Header.Set("X-Apple-I-Client-Bundle-Id", "com.apple.MobileSMS")
	req.Header.Set("X-MMe-Client-Info", g.clientInfo)
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("User-Agent", g.akdUA)
}

func (g *gsaClient) post(body interface{}) (map[string]interface{}, error) {
	buf, err := plist.MarshalIndent(body, plist.XMLFormat, "\t")
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequest("POST", gsaEndpoint, bytes.NewReader(buf))
	g.serviceHeaders(req)
	resp, err := g.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]interface{}
	if _, err := plist.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("GSA reply not a plist (HTTP %d)", resp.StatusCode)
	}
	if inner, ok := out["Response"].(map[string]interface{}); ok {
		out = inner
	}
	return out, gsaError(out)
}

// GSAError carries Apple's GrandSlam status code; -22406 is "wrong password".
type GSAError struct {
	Code    int64
	Message string
}

func (e *GSAError) Error() string { return fmt.Sprintf("GSA error %d: %s", e.Code, e.Message) }

func gsaError(res map[string]interface{}) error {
	st, ok := res["Status"].(map[string]interface{})
	if !ok {
		st = res
	}
	if ec := toInt(st["ec"]); ec != 0 {
		em, _ := st["em"].(string)
		return &GSAError{Code: ec, Message: em}
	}
	return nil
}

type gsaSession struct {
	Username  string
	ADSID     string
	DSID      int64
	IdmsToken string // GsIdmsToken, used for X-Apple-Identity-Token during 2FA
	PET       string // com.apple.gs.idms.pet
	AuthState string // Status.au, e.g. trustedDeviceSecondaryAuth
}

func (g *gsaClient) login(username, password string) (*gsaSession, error) {
	srp := newSRPClient()
	reqID := NewUUID()
	initRes, err := g.post(map[string]interface{}{
		"Header": map[string]interface{}{"Version": "1.0.1"},
		"Request": map[string]interface{}{
			"A2k": srp.APub,
			"cpd": g.cpd(reqID),
			"o":   "init",
			"ps":  []string{"s2k", "s2k_fo"},
			"u":   username,
		},
	})
	if err != nil {
		return nil, fmt.Errorf("init: %w", err)
	}
	salt, _ := initRes["s"].([]byte)
	bPub, _ := initRes["B"].([]byte)
	iter := int(toInt(initRes["i"]))
	c, _ := initRes["c"].(string)
	sp, _ := initRes["sp"].(string)
	if sp == "" {
		sp = "s2k"
	}
	if len(salt) == 0 || len(bPub) == 0 || iter == 0 || c == "" {
		return nil, errors.New("SRP init reply missing s/B/i/c")
	}

	res, err := srp.process(username, gsaPasswordKey(password, sp, salt, iter), salt, bPub)
	if err != nil {
		return nil, err
	}
	done, err := g.post(map[string]interface{}{
		"Header": map[string]interface{}{"Version": "1.0.1"},
		"Request": map[string]interface{}{
			"M1":  res.M1,
			"c":   c,
			"cpd": g.cpd(reqID),
			"o":   "complete",
			"u":   username,
		},
	})
	if err != nil {
		return nil, fmt.Errorf("complete: %w", err)
	}
	if m2, _ := done["M2"].([]byte); !bytes.Equal(m2, res.M2) {
		return nil, errors.New("server SRP proof mismatch")
	}

	spdEnc, _ := done["spd"].([]byte)
	spdRaw, err := res.decryptSPD(spdEnc)
	if err != nil {
		return nil, fmt.Errorf("spd: %w", err)
	}
	var spd map[string]interface{}
	if _, err := plist.Unmarshal(spdRaw, &spd); err != nil {
		return nil, fmt.Errorf("spd plist: %w", err)
	}
	s := &gsaSession{Username: username}
	s.ADSID, _ = spd["adsid"].(string)
	s.DSID = toInt(spd["DsPrsId"])
	s.IdmsToken, _ = spd["GsIdmsToken"].(string)
	if acname, ok := spd["acname"].(string); ok && acname != "" {
		s.Username = acname
	}
	if t, ok := spd["t"].(map[string]interface{}); ok {
		if d, ok := t["com.apple.gs.idms.pet"].(map[string]interface{}); ok {
			s.PET, _ = d["token"].(string)
		}
	}
	if st, ok := done["Status"].(map[string]interface{}); ok {
		s.AuthState, _ = st["au"].(string)
	}
	logf("GSA login ok: adsid=%s… pet=%v au=%q", short(s.ADSID), s.PET != "", s.AuthState)
	return s, nil
}

func (g *gsaClient) twoFAHeaders(req *http.Request, s *gsaSession, plistBody bool) {
	for k, v := range g.ani.pick(mdKeys...) {
		req.Header.Set(k, v)
	}
	for k, v := range map[string]string{
		"X-Apple-Client-App-Name":             "Messages",
		"X-Apple-I-Client-Bundle-Id":          "com.apple.MobileSMS",
		"X-MMe-Client-Info":                   g.clientInfo,
		"X-Apple-I-CDP-Circle-Status":         "false",
		"X-Apple-I-ICSCREC":                   "true",
		"User-Agent":                          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)",
		"Sec-Fetch-Site":                      "same-origin",
		"X-Apple-Requested-Partition":         "0",
		"X-Apple-I-DeviceUserMode":            "0",
		"X-Apple-I-Locale":                    "en_US",
		"X-Apple-Security-Upgrade-Context":    "com.apple.authkit.generic",
		"Accept-Language":                     "en-US,en;q=0.9",
		"X-Apple-I-PRK-Gen":                   "true",
		"Sec-Fetch-Mode":                      "cors",
		"X-Apple-I-TimeZone":                  "UTC",
		"X-Apple-I-OT-Status":                 "false",
		"X-Apple-I-TimeZone-Offset":           "0",
		"X-MMe-Country":                       "US",
		"X-Apple-I-CDP-Status":                "false",
		"X-Apple-I-Device-Configuration-Mode": "0",
		"Sec-Fetch-Dest":                      "empty",
		"X-Apple-AK-Context-Type":             "imessage",
	} {
		req.Header.Set(k, v)
	}
	if plistBody {
		req.Header.Set("Content-Type", "text/x-xml-plist")
		req.Header.Set("Accept", "text/x-xml-plist")
	}
	req.Header.Set("X-Apple-Identity-Token", base64.StdEncoding.EncodeToString([]byte(s.ADSID+":"+s.IdmsToken)))
}

// pushTrustedDevice asks Apple to show a code on the user's trusted Apple devices.
func (g *gsaClient) pushTrustedDevice(s *gsaSession) error {
	req, _ := http.NewRequest("GET", "https://gsa.apple.com/auth/verify/trusteddevice", nil)
	g.twoFAHeaders(req, s, true)
	resp, err := g.http.Do(req)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("trusted device push HTTP %d", resp.StatusCode)
	}
	return nil
}

func (g *gsaClient) verifyTrustedCode(s *gsaSession, code string) error {
	req, _ := http.NewRequest("GET", gsaEndpoint+"/validate", nil)
	g.twoFAHeaders(req, s, true)
	req.Header.Set("security-code", code)
	resp, err := g.http.Do(req)
	if err != nil {
		return err
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	var out map[string]interface{}
	if _, err := plist.Unmarshal(raw, &out); err != nil {
		return fmt.Errorf("validate reply not a plist (HTTP %d)", resp.StatusCode)
	}
	return gsaError(out)
}

func (g *gsaClient) requestSMS(s *gsaSession, phoneID int) error {
	body, _ := json.Marshal(map[string]interface{}{"phoneNumber": map[string]int{"id": phoneID}, "mode": "sms"})
	req, _ := http.NewRequest("PUT", "https://gsa.apple.com/auth/verify/phone", bytes.NewReader(body))
	g.twoFAHeaders(req, s, false)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := g.http.Do(req)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("sms request HTTP %d", resp.StatusCode)
	}
	return nil
}

func (g *gsaClient) verifySMS(s *gsaSession, phoneID int, code string) error {
	body, _ := json.Marshal(map[string]interface{}{
		"phoneNumber":  map[string]int{"id": phoneID},
		"mode":         "sms",
		"securityCode": map[string]string{"code": code},
	})
	req, _ := http.NewRequest("POST", "https://gsa.apple.com/auth/verify/phone/securitycode", bytes.NewReader(body))
	g.twoFAHeaders(req, s, false)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := g.http.Do(req)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("sms code rejected HTTP %d", resp.StatusCode)
	}
	return nil
}
