package native

// Login flow and the long-lived credential blob.
//
// What is persisted (by the Android side, in EncryptedSharedPreferences) is only what upload
// needs later: dsid + mmeAuthToken, plus the per-install identifiers below. The password and the
// short-lived PET are never part of it. cloudKitToken is re-derived from mmeAuthToken on every
// upload, so nothing here has to be written back after use.

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Credentials is the persisted native-login result.
type Credentials struct {
	AppleID  string `json:"appleId"`
	DSID     string `json:"dsid"`
	ADSID    string `json:"adsid"`
	MMEToken string `json:"mmeAuthToken"`
	// Stable per install so every CloudKit call presents the same device.
	DeviceUUID string `json:"deviceUuid"`
	HardwareID string `json:"hardwareId"`
	// BoundarySecret seeds the V2 boundary key, so the same file always produces the same
	// fileChecksum and therefore the same CPLMaster recordName. That keeps the pre-upload
	// duplicate lookup meaningful (AGENTS §4.6 / ISS-068). Random per install.
	BoundarySecret string    `json:"boundarySecret"`
	CreatedAt      time.Time `json:"createdAt"`
}

func (c *Credentials) Valid() bool {
	return c != nil && c.DSID != "" && c.MMEToken != "" && c.DeviceUUID != "" && c.BoundarySecret != ""
}

func ParseCredentials(s string) (*Credentials, error) {
	var c Credentials
	if err := json.Unmarshal([]byte(s), &c); err != nil {
		return nil, err
	}
	if !c.Valid() {
		return nil, errors.New("credentials incomplete")
	}
	return &c, nil
}

func (c *Credentials) JSON() string {
	b, _ := json.Marshal(c)
	return string(b)
}

// Login states reported to the UI.
const (
	LoginDone     = "DONE"
	LoginNeedCode = "NEED_CODE"
)

// LoginFlow holds one in-progress login between Begin and SubmitCode.
type LoginFlow struct {
	mu       sync.Mutex
	g        *gsaClient
	ani      Anisette
	appleID  string
	password string
	s        *gsaSession
	sms      bool
	phoneID  int
}

// BeginLogin runs SRP. When Apple wants a second factor it pushes a code to the user's trusted
// devices and returns LoginNeedCode; otherwise it finishes and returns the credentials.
func BeginLogin(anisetteURL, appleID, password string) (*LoginFlow, string, *Credentials, error) {
	appleID = strings.TrimSpace(appleID)
	if appleID == "" || password == "" {
		return nil, "", nil, errors.New("Apple ID and password are required")
	}
	ani, err := FetchAnisette(anisetteURL)
	if err != nil {
		return nil, "", nil, err
	}
	f := &LoginFlow{g: newGSAClient(ani), ani: ani, appleID: appleID, password: password}
	s, err := f.g.login(appleID, password)
	if err != nil {
		f.forget()
		return nil, "", nil, err
	}
	f.s = s
	// Check the auth state FIRST. With a new device (e.g. an iCloud-for-Windows identity) Apple
	// returns a PET *and* au=trustedDeviceSecondaryAuth: the PET is not usable until the 2FA
	// code is verified, and treating it as done fails later at MobileMe with HTTP 401.
	switch s.AuthState {
	case "":
		if s.PET == "" {
			f.forget()
			return nil, "", nil, errors.New("Apple returned neither a token nor a 2FA request")
		}
		c, err := f.finish()
		return nil, LoginDone, c, err
	case "trustedDeviceSecondaryAuth":
		if err := f.g.pushTrustedDevice(s); err != nil {
			logf("trusted-device push failed (%v); SMS needed", err)
			f.sms = true
		}
	case "secondaryAuth":
		f.sms = true
	default:
		if s.PET != "" {
			// Unknown non-2FA state with a token: try it rather than block the user.
			logf("auth state %q with PET; finishing", s.AuthState)
			c, err := f.finish()
			return nil, LoginDone, c, err
		}
		f.forget()
		return nil, "", nil, fmt.Errorf("unexpected auth state %q", s.AuthState)
	}
	if f.sms {
		f.phoneID = 1
		if err := f.g.requestSMS(s, f.phoneID); err != nil {
			f.forget()
			return nil, "", nil, err
		}
	}
	return f, LoginNeedCode, nil, nil
}

// UsesSMS reports whether the code was sent by SMS rather than to a trusted device.
func (f *LoginFlow) UsesSMS() bool { return f.sms }

// SwitchToSMS sends the code by SMS instead (for users without a trusted Apple device nearby).
func (f *LoginFlow) SwitchToSMS() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.s == nil {
		return errors.New("login flow already finished")
	}
	f.sms, f.phoneID = true, 1
	return f.g.requestSMS(f.s, f.phoneID)
}

// SubmitCode verifies the 2FA code, logs in again to collect the PET, and returns credentials.
func (f *LoginFlow) SubmitCode(code string) (*Credentials, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.s == nil {
		return nil, errors.New("login flow already finished")
	}
	code = strings.TrimSpace(code)
	var err error
	if f.sms {
		err = f.g.verifySMS(f.s, f.phoneID, code)
	} else {
		err = f.g.verifyTrustedCode(f.s, code)
	}
	if err != nil {
		return nil, fmt.Errorf("verification code rejected: %w", err)
	}
	s, err := f.g.login(f.appleID, f.password)
	if err != nil {
		f.forget()
		return nil, err
	}
	if s.PET == "" {
		f.forget()
		return nil, fmt.Errorf("no PET after 2FA (state %q)", s.AuthState)
	}
	f.s = s
	return f.finish()
}

func (f *LoginFlow) finish() (*Credentials, error) {
	defer f.forget()
	t, err := mobileMeLogin(f.ani, f.s)
	if err != nil {
		return nil, err
	}
	dsid := t.DSID
	if dsid == "" || dsid == "<nil>" {
		dsid = strconv.FormatInt(f.s.DSID, 10)
	}
	return &Credentials{
		AppleID:        f.s.Username,
		DSID:           dsid,
		ADSID:          f.s.ADSID,
		MMEToken:       t.Tokens["mmeAuthToken"],
		DeviceUUID:     NewUUID(),
		HardwareID:     NewUUID(),
		BoundarySecret: b64(randBytes(32)),
		CreatedAt:      time.Now(),
	}, nil
}

// forget drops the password and session material.
func (f *LoginFlow) forget() {
	f.password = ""
	f.s = nil
}

// Abandon is called when the UI cancels a pending login.
func (f *LoginFlow) Abandon() {
	f.mu.Lock()
	f.forget()
	f.mu.Unlock()
}
