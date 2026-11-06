package icloudcore

import "testing"

// installSessionForTest makes s the process-global session for the duration of the test.
//
// ## Why this has to exist
//
// `NewSession()` does NOT install the session into `currentSession` — it only builds and returns
// a `*Session`. Every exported entry point (`CheckPrimaryZone`, `RetrieveItemDetails`,
// `FetchPhotoPage`, `UploadPhotoAssetByPath`, …) reads the package-level `currentSession` through
// `GetSession()`.
//
// That split is deliberate: `InitSession()` installs a session only AFTER `ValidateSession()`
// succeeds (ISS-063 — two concurrent `NewSession` calls used to overwrite the global with a
// half-validated session that had no `CKDatabaseURL`). But it silently broke every test that
// configured the returned struct and then called an API function: the configured fields were
// written to an object nothing ever read, so the call saw "No active session" and failed with
// `AUTH_EXPIRED`.
//
// The tests are otherwise correct — they assert real behaviour (ID normalisation, Range resume,
// 401/404 error mapping, orphan-master filtering, Live Photo MIME detection). Only the installation
// step was missing, so this helper is a one-line fix at each call site rather than a rewrite.
//
// It also clears the global on cleanup. Without that, a test that forgot to reset leaves a session
// pointing at a closed `httptest` server, and the *next* test reports `NETWORK` / connection-refused
// instead of its real failure — which is why these used to fail differently depending on run order.
func installSessionForTest(t *testing.T, s *Session) {
	t.Helper()
	sessionMu.Lock()
	currentSession = s
	sessionMu.Unlock()
	t.Cleanup(resetSessionForTest)
}

// newTestSession builds a China-mainland session suitable for pointing at an httptest server.
//
// Prefer this over calling `NewSession` directly in tests: it fails fast with a useful message and
// removes the repeated 4-line error-check boilerplate.
func newTestSession(t *testing.T) *Session {
	t.Helper()
	s, err := NewSession("test=cookie", true)
	if err != nil {
		t.Fatalf("NewSession failed: %v", err)
	}
	return s
}
