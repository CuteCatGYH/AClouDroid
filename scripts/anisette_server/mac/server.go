package main

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	appVersion          = "1.0.0"
	platformName        = "macos"
	rateLimitMaxReqs    = 30
	rateLimitWindow     = 60 * time.Second
	maxRequestHeaderLen = 8 << 10 // 8 KiB
)

type rateLimiter struct {
	mu     sync.Mutex
	window time.Duration
	limit  int
	times  []time.Time
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{
		window: window,
		limit:  limit,
		times:  make([]time.Time, 0, limit),
	}
}

func (rl *rateLimiter) allow(now time.Time) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	cutoff := now.Add(-rl.window)
	keep := 0
	for _, t := range rl.times {
		if t.After(cutoff) {
			rl.times[keep] = t
			keep++
		}
	}
	rl.times = rl.times[:keep]
	if len(rl.times) >= rl.limit {
		return false
	}
	rl.times = append(rl.times, now)
	return true
}

type anisetteHandler struct {
	token   []byte
	limiter *rateLimiter
	guard   *bruteGuard
	compute func() (map[string]string, error)
}

// bruteGuard blocks an IP that keeps guessing the token: after bruteMaxFails wrong requests
// within bruteWindow it gets 404 for everything (even the right token) for bruteBlock.
type bruteGuard struct {
	mu    sync.Mutex
	fails map[string][]time.Time
	until map[string]time.Time
}

const (
	bruteMaxFails = 10
	bruteWindow   = 10 * time.Minute
	bruteBlock    = 30 * time.Minute
	bruteMaxIPs   = 4096 // memory cap; oldest entries are dropped past this
)

func newBruteGuard() *bruteGuard {
	return &bruteGuard{fails: map[string][]time.Time{}, until: map[string]time.Time{}}
}

func (g *bruteGuard) blocked(ip string, now time.Time) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if t, ok := g.until[ip]; ok {
		if now.Before(t) {
			return true
		}
		delete(g.until, ip)
	}
	return false
}

func (g *bruteGuard) fail(ip string, now time.Time) {
	// A tunnel (cloudflared, frp) makes every visitor look like loopback. Blocking it would let
	// one stranger lock the owner out, so loopback is never blocked; the 256-bit token is what
	// actually stops guessing.
	if parsed := net.ParseIP(ip); parsed != nil && parsed.IsLoopback() {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if len(g.fails) >= bruteMaxIPs {
		for k := range g.fails { // map order is random: drop one arbitrary entry
			delete(g.fails, k)
			break
		}
	}
	cutoff := now.Add(-bruteWindow)
	kept := g.fails[ip][:0]
	for _, t := range g.fails[ip] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	kept = append(kept, now)
	if len(kept) >= bruteMaxFails {
		g.until[ip] = now.Add(bruteBlock)
		delete(g.fails, ip)
		fmt.Fprintf(os.Stdout, "%s %s blocked for %s after %d wrong requests\n", now.Format(time.RFC3339), ip, bruteBlock, len(kept))
		return
	}
	g.fails[ip] = kept
}

func newAnisetteHandler(token string, compute func() (map[string]string, error)) *anisetteHandler {
	return &anisetteHandler{
		token:   []byte(token),
		limiter: newRateLimiter(rateLimitMaxReqs, rateLimitWindow),
		guard:   newBruteGuard(),
		compute: compute,
	}
}

func (h *anisetteHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	remoteIP := extractRemoteIP(r.RemoteAddr)
	now := time.Now()

	if h.guard.blocked(remoteIP, now) {
		w.WriteHeader(http.StatusNotFound)
		logRequest(remoteIP, "blocked", http.StatusNotFound)
		return
	}

	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		w.WriteHeader(http.StatusMethodNotAllowed)
		logRequest(remoteIP, "denied", http.StatusMethodNotAllowed)
		return
	}

	pathClass, authorized := h.matchRoute(r.URL.Path)
	if !authorized {
		h.guard.fail(remoteIP, now)
		w.WriteHeader(http.StatusNotFound)
		logRequest(remoteIP, "denied", http.StatusNotFound)
		return
	}

	if pathClass == "health" {
		body, _ := json.Marshal(struct {
			OK       bool   `json:"ok"`
			Platform string `json:"platform"`
			Version  string `json:"version"`
		}{
			OK:       true,
			Platform: platformName,
			Version:  appVersion,
		})
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		if r.Method != http.MethodHead {
			_, _ = w.Write(body)
		}
		logRequest(remoteIP, "health", http.StatusOK)
		return
	}

	// pathClass == "anisette"
	if !h.limiter.allow(time.Now()) {
		w.WriteHeader(http.StatusTooManyRequests)
		logRequest(remoteIP, "anisette", http.StatusTooManyRequests)
		return
	}

	headers, err := h.compute()
	if err != nil {
		writeJSONError(w, r.Method, http.StatusInternalServerError, err.Error())
		logRequest(remoteIP, "anisette", http.StatusInternalServerError)
		return
	}

	body, err := json.Marshal(headers)
	if err != nil {
		writeJSONError(w, r.Method, http.StatusInternalServerError, "failed to encode response")
		logRequest(remoteIP, "anisette", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(body)
	}
	logRequest(remoteIP, "anisette", http.StatusOK)
}

func (h *anisetteHandler) matchRoute(rawPath string) (string, bool) {
	if !strings.HasPrefix(rawPath, "/") {
		return "denied", false
	}
	rest := rawPath[1:]
	var candidate, sub string
	if idx := strings.IndexByte(rest, '/'); idx >= 0 {
		candidate = rest[:idx]
		sub = rest[idx+1:]
	} else {
		candidate = rest
		sub = ""
	}

	tokenOK := subtle.ConstantTimeCompare([]byte(candidate), h.token) == 1
	if !tokenOK {
		return "denied", false
	}

	switch sub {
	case "":
		return "anisette", true
	case "health":
		return "health", true
	default:
		return "denied", false
	}
}

func writeJSONError(w http.ResponseWriter, method string, status int, reason string) {
	body, _ := json.Marshal(struct {
		Error string `json:"error"`
	}{Error: reason})
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if method != http.MethodHead {
		_, _ = w.Write(body)
	}
}

func extractRemoteIP(remoteAddr string) string {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		return remoteAddr
	}
	return host
}

func logRequest(remoteIP, pathClass string, status int) {
	fmt.Fprintf(os.Stdout, "%s %s %s %d\n", time.Now().Format(time.RFC3339), remoteIP, pathClass, status)
}

func newHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		MaxHeaderBytes:    maxRequestHeaderLen,
		ReadTimeout:       10 * time.Second,
		ReadHeaderTimeout: 10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
}
