package icloudcore

// gomobile surface for native (device-style) iCloud access, used only to upload files that the
// 云上贵州 web gateway rejects (> 1 GiB, ISS-057). See go-core/native for the protocol.
//
// State lives in process memory. Kotlin owns persistence: it stores the credentials JSON that a
// successful login returns (EncryptedSharedPreferences) and hands it back through
// NativeConfigure on every cold start. The password never leaves the login calls.

import (
	"encoding/json"
	"errors"
	"sync"

	"icloudcore/native"
)

var (
	nativeMu       sync.Mutex
	nativeCreds    *native.Credentials
	nativeAnisette string
	nativePending  *native.LoginFlow
)

type NativeLoginResult struct {
	ResultBase
	// State is DONE or NEED_CODE.
	State string `json:"state,omitempty"`
	// SMS is true when the code was sent by text message instead of to an Apple device.
	SMS bool `json:"sms,omitempty"`
	// Credentials is the blob to persist; only present when State == DONE.
	Credentials string `json:"credentials,omitempty"`
	AppleID     string `json:"appleId,omitempty"`
}

type NativeStatusResult struct {
	ResultBase
	Configured  bool   `json:"configured"`
	AppleID     string `json:"appleId,omitempty"`
	AnisetteURL string `json:"anisetteUrl,omitempty"`
}

func marshalJSON(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func nativeFail(code, msg string) ResultBase { return failBase(code, 0, msg) }

func classifyNativeErr(err error) string {
	var gsa *native.GSAError
	switch {
	case errors.Is(err, native.ErrAuthExpired):
		return ErrCodeNativeAuthExpired
	case errors.As(err, &gsa):
		return ErrCodeNativeAuthExpired
	default:
		if c := ClassifyNetErr(err); c == ErrCodeTimeout {
			return c
		}
		return ErrCodeNativeUnavailable
	}
}

// NativeConfigure installs persisted credentials (may be "") and the fingerprint provider URL.
func NativeConfigure(credentialsJSON string, anisetteURL string) string {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	nativeAnisette = anisetteURL
	nativeCreds = nil
	if credentialsJSON != "" {
		c, err := native.ParseCredentials(credentialsJSON)
		if err != nil {
			return marshalJSON(NativeStatusResult{ResultBase: nativeFail(ErrCodeParse, "stored native credentials unreadable")})
		}
		nativeCreds = c
	}
	return nativeStatusLocked()
}

// NativeStatus reports whether native upload is ready to use.
func NativeStatus() string {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	return nativeStatusLocked()
}

func nativeStatusLocked() string {
	r := NativeStatusResult{ResultBase: okBase(), AnisetteURL: nativeAnisette}
	if nativeCreds != nil {
		r.Configured = nativeAnisette != ""
		r.AppleID = nativeCreds.AppleID
	}
	return marshalJSON(r)
}

// NativeLoginBegin starts an Apple ID login. Returns NEED_CODE when a verification code was
// sent, or DONE with credentials when Apple did not ask for one.
func NativeLoginBegin(anisetteURL string, appleID string, password string) string {
	nativeMu.Lock()
	if nativePending != nil {
		nativePending.Abandon()
		nativePending = nil
	}
	nativeAnisette = anisetteURL
	nativeMu.Unlock()

	flow, state, creds, err := native.BeginLogin(anisetteURL, appleID, password)
	password = ""
	if err != nil {
		return marshalJSON(NativeLoginResult{ResultBase: nativeFail(classifyNativeErr(err), err.Error())})
	}
	if state == native.LoginDone {
		return nativeLoginDone(creds)
	}
	nativeMu.Lock()
	nativePending = flow
	nativeMu.Unlock()
	return marshalJSON(NativeLoginResult{ResultBase: okBase(), State: state, SMS: flow.UsesSMS()})
}

// NativeLoginUseSMS re-sends the pending login's code by text message.
func NativeLoginUseSMS() string {
	nativeMu.Lock()
	flow := nativePending
	nativeMu.Unlock()
	if flow == nil {
		return marshalJSON(NativeLoginResult{ResultBase: nativeFail(ErrCodeUnknown, "no login in progress")})
	}
	if err := flow.SwitchToSMS(); err != nil {
		return marshalJSON(NativeLoginResult{ResultBase: nativeFail(classifyNativeErr(err), err.Error())})
	}
	return marshalJSON(NativeLoginResult{ResultBase: okBase(), State: native.LoginNeedCode, SMS: true})
}

// NativeLoginSubmitCode finishes the pending login with the verification code.
func NativeLoginSubmitCode(code string) string {
	nativeMu.Lock()
	flow := nativePending
	nativeMu.Unlock()
	if flow == nil {
		return marshalJSON(NativeLoginResult{ResultBase: nativeFail(ErrCodeUnknown, "no login in progress")})
	}
	creds, err := flow.SubmitCode(code)
	if err != nil {
		// A wrong code keeps the flow alive so the user can retype it.
		return marshalJSON(NativeLoginResult{ResultBase: nativeFail(classifyNativeErr(err), err.Error()), State: native.LoginNeedCode, SMS: flow.UsesSMS()})
	}
	nativeMu.Lock()
	nativePending = nil
	nativeMu.Unlock()
	return nativeLoginDone(creds)
}

func nativeLoginDone(c *native.Credentials) string {
	nativeMu.Lock()
	nativeCreds = c
	nativeMu.Unlock()
	return marshalJSON(NativeLoginResult{ResultBase: okBase(), State: native.LoginDone, Credentials: c.JSON(), AppleID: c.AppleID})
}

// NativeLoginCancel drops a pending login (and the password it holds).
func NativeLoginCancel() {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	if nativePending != nil {
		nativePending.Abandon()
		nativePending = nil
	}
}

// NativeLogout forgets the in-memory credentials. Kotlin deletes its stored copy.
func NativeLogout() {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	nativeCreds = nil
	if nativePending != nil {
		nativePending.Abandon()
		nativePending = nil
	}
}

// NativeCheck opens the Photos zone with the current credentials: proves the fingerprint
// provider is reachable and the login still works, without uploading anything.
func NativeCheck() string {
	creds, ani := nativeSnapshot()
	if creds == nil {
		return marshalJSON(NativeStatusResult{ResultBase: nativeFail(ErrCodeRegionUploadLimit, "native upload not logged in")})
	}
	if _, err := native.NewPhotosUploader(ani, creds); err != nil {
		return marshalJSON(NativeStatusResult{ResultBase: nativeFail(classifyNativeErr(err), err.Error()), Configured: true, AppleID: creds.AppleID})
	}
	return marshalJSON(NativeStatusResult{ResultBase: okBase(), Configured: true, AppleID: creds.AppleID, AnisetteURL: ani})
}

func nativeSnapshot() (*native.Credentials, string) {
	nativeMu.Lock()
	defer nativeMu.Unlock()
	if nativeCreds == nil || nativeAnisette == "" {
		return nil, nativeAnisette
	}
	c := *nativeCreds
	return &c, nativeAnisette
}
