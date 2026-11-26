//go:build livefake

package native

// Dev-only live probe (build tag livefake): which native calls really check the fingerprint?
// Each step runs with real Mac anisette, then with random fake anisette.
//
//	go test -tags livefake -run TestLiveFakeAnisette -v ./native/

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func probeCreds(t *testing.T) *Credentials {
	home, _ := os.UserHomeDir()
	var st struct {
		DSID string `json:"dsid"`
		MME  struct {
			DSID   string            `json:"dsid"`
			Tokens map[string]string `json:"tokens"`
		} `json:"mme"`
	}
	raw, err := os.ReadFile(filepath.Join(home, ".config", "iclouddroid-probe", "state.json"))
	if err != nil || json.Unmarshal(raw, &st) != nil {
		t.Skip("no probe state")
	}
	dsid := st.MME.DSID
	if dsid == "" || dsid == "<nil>" {
		dsid = st.DSID
	}
	return &Credentials{DSID: dsid, MMEToken: st.MME.Tokens["mmeAuthToken"], DeviceUUID: NewUUID(), HardwareID: NewUUID(),
		BoundarySecret: b64(randBytes(32)), CreatedAt: time.Now()}
}

func status(err error) string {
	if err == nil {
		return "OK"
	}
	return err.Error()
}

func TestLivePublicAnisette(t *testing.T) {
	creds := probeCreds(t)
	for _, u := range strings.Fields(os.Getenv("ANI_URLS")) {
		a, err := FetchAnisette(u)
		if err != nil {
			t.Logf("%-36s fetch: %v", u, err)
			continue
		}
		_, st, err := accountSettings(a, creds.DSID, creds.MMEToken)
		t.Logf("%-36s get_account_settings -> HTTP %d %s", u, st, status(err))
	}
}

func TestLiveStaleAnisette(t *testing.T) {
	creds := probeCreds(t)
	real, err := FetchAnisette("http://127.0.0.1:6969/")
	if err != nil {
		t.Skip(err)
	}
	fakeMD := Anisette{}
	for k, v := range real {
		fakeMD[k] = v
	}
	fakeMD["X-Apple-I-MD"] = b64(randBytes(28))
	_, st, err := accountSettings(fakeMD, creds.DSID, creds.MMEToken)
	t.Logf("real machine id + random one-time code -> HTTP %d %s", st, status(err))

	wait := 0
	if v := os.Getenv("STALE_MINUTES"); v != "" {
		for _, c := range v {
			wait = wait*10 + int(c-'0')
		}
	}
	for i := 0; i <= wait; i++ {
		if i > 0 {
			time.Sleep(time.Minute)
		}
		_, st, err := accountSettings(real, creds.DSID, creds.MMEToken)
		t.Logf("same captured fingerprint after %2d min -> HTTP %d %s", i, st, status(err))
	}
}

func TestLiveFakeAnisette(t *testing.T) {
	creds := probeCreds(t)
	real, err := FetchAnisette("http://127.0.0.1:6969/")
	if err != nil {
		t.Skip(err)
	}
	fake, err := FetchAnisette("http://127.0.0.1:6971/")
	if err != nil {
		t.Skip(err)
	}
	for name, a := range map[string]Anisette{"real": real, "fake": fake} {
		_, st, err := accountSettings(a, creds.DSID, creds.MMEToken)
		t.Logf("get_account_settings %-4s -> HTTP %d %s", name, st, status(err))
	}

	// cloudKitToken from a real call, then everything after it with fake anisette.
	tk, _, err := accountSettings(real, creds.DSID, creds.MMEToken)
	if err != nil {
		t.Fatal(err)
	}
	hw, sw := deviceVersions(fake["X-Mme-Client-Info"])
	s := &ckSession{ani: fake, creds: creds, container: PhotosContainer, bundle: PhotosBundle, zone: PhotosZone,
		cloudKitTok: tk.Tokens["cloudKitToken"], hwVersion: hw, swVersion: sw, opGroup: randHexID(), op: randHexID()}
	uid, err := s.appInit()
	t.Logf("ckAppInit            fake -> %s", status(err))
	if err != nil {
		return
	}
	s.userID = uid

	data := strings.NewReader(strings.Repeat("iclouddroid fake anisette probe ", 200000))
	bk, _ := boundaryKey(creds.BoundarySecret)
	pp, err := preparePutV2(data, int64(data.Len()), bk)
	if err != nil {
		t.Fatal(err)
	}
	u := &PhotosUploader{s: s, bk: bk}
	rc, err := u.Upload(&Prepared{pp: pp}, data, nil)
	if err != nil {
		t.Logf("upload (token+MMCS)  fake -> %s", status(err))
		return
	}
	t.Logf("upload (token+MMCS)  fake -> OK, receipt %d chars", len(rc.Receipt))
}
