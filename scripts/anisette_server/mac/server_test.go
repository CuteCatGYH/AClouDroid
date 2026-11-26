package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestBruteGuardBlocksAfterTenMisses(t *testing.T) {
	h := newAnisetteHandler("right-token", func() (map[string]string, error) {
		return map[string]string{"X-Apple-I-MD": "a", "X-Apple-I-MD-M": "b", "X-Mme-Device-Id": "c"}, nil
	})
	get := func(ip, path string) int {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		r.RemoteAddr = ip + ":5555"
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	for i := 0; i < bruteMaxFails-1; i++ {
		get("203.0.113.7", "/guess/")
	}
	if c := get("203.0.113.7", "/right-token/"); c != 200 {
		t.Fatalf("9 misses must not block yet, got %d", c)
	}
	get("203.0.113.7", "/guess/") // 10th miss
	if c := get("203.0.113.7", "/right-token/"); c != 404 {
		t.Fatalf("blocked IP must get 404 even with the right token, got %d", c)
	}
	if c := get("198.51.100.9", "/right-token/"); c != 200 {
		t.Fatalf("other IPs must be unaffected, got %d", c)
	}
	// Loopback (tunnels) is never blocked.
	for i := 0; i < bruteMaxFails*2; i++ {
		get("127.0.0.1", "/guess/")
	}
	if c := get("127.0.0.1", "/right-token/"); c != 200 {
		t.Fatalf("loopback must never be blocked, got %d", c)
	}
	// The block expires.
	h.guard.until["203.0.113.7"] = time.Now().Add(-time.Second)
	if c := get("203.0.113.7", "/right-token/"); c != 200 {
		t.Fatalf("expired block must lift, got %d", c)
	}
}
