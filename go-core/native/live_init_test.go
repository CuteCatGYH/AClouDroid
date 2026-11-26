//go:build livefake

package native

// Dev-only: run only the GSA "init" step (Apple ID, no password) against an anisette URL, with
// optional header overrides, to find which fingerprint field Apple rejects.
//
//	INIT_URL=... INIT_USER=... go test -tags livefake -run TestLiveInitOnly -v ./native/

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func TestLiveInitOnly(t *testing.T) {
	url, user := os.Getenv("INIT_URL"), os.Getenv("INIT_USER")
	if url == "" || user == "" {
		t.Skip("INIT_URL / INIT_USER not set")
	}
	variants := map[string]map[string]string{"as-is": {}}
	if j := os.Getenv("INIT_VARIANTS_JSON"); j != "" {
		if err := json.Unmarshal([]byte(j), &variants); err != nil {
			t.Fatalf("INIT_VARIANTS_JSON: %v", err)
		}
	}
	for _, v := range strings.Split(os.Getenv("INIT_VARIANTS"), ";") {
		// name=Key:Value,Key:Value
		if v == "" {
			continue
		}
		parts := strings.SplitN(v, "=", 2)
		ov := map[string]string{}
		for _, kv := range strings.Split(parts[1], ",") {
			p := strings.SplitN(kv, ":", 2)
			ov[p[0]] = p[1]
		}
		variants[parts[0]] = ov
	}
	for name, ov := range variants {
		a, err := FetchAnisette(url)
		if err != nil {
			t.Fatalf("fetch: %v", err)
		}
		for k, v := range ov {
			if v == "-" {
				delete(a, k)
			} else {
				a[k] = v
			}
		}
		g := newGSAClient(a)
		if ci := ov["CLIENT_INFO_FULL"]; ci != "" {
			g.clientInfo = ci // exact header, bypassing the akd suffix rewrite
		}
		srp := newSRPClient()
		_, err = g.post(map[string]interface{}{
			"Header": map[string]interface{}{"Version": "1.0.1"},
			"Request": map[string]interface{}{
				"A2k": srp.APub, "cpd": g.cpd(NewUUID()), "o": "init",
				"ps": []string{"s2k", "s2k_fo"}, "u": user,
			},
		})
		t.Logf("%-12s init -> %v", name, status(err))
	}
}
