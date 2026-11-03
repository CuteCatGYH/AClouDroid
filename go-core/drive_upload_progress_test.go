package icloudcore

import (
	"encoding/json"
	"strings"
	"testing"
)

// The "stuck at 传输中 for minutes" bug was not in the transfer at all — it was that the
// SHA-256 pass runs over the whole file before any network traffic, and the progress map was
// never updated during it. These tests pin the phase contract the Kotlin poller depends on.

func TestUploadProgressReportsHashPhase(t *testing.T) {
	key := "/tmp/phase-hash-test.bin"
	defer clearDriveUploadProgress(key)

	updateDriveUploadProgressPhase(key, 512, 1024, PhaseHash)
	progress := readProgress(t, key)

	if progress.Phase != PhaseHash {
		t.Errorf("expected phase %q, got %q", PhaseHash, progress.Phase)
	}
	if progress.BytesUploaded != 512 || progress.TotalBytes != 1024 {
		t.Errorf("expected 512/1024, got %d/%d", progress.BytesUploaded, progress.TotalBytes)
	}
	if progress.Progress != 50 {
		t.Errorf("expected 50%%, got %f", progress.Progress)
	}
}

func TestUploadProgressPhaseTransitions(t *testing.T) {
	key := "/tmp/phase-transition-test.bin"
	defer clearDriveUploadProgress(key)

	// Hash completes → upload starts from 0. The bar must not stay pinned at 100%.
	updateDriveUploadProgressPhase(key, 4096, 4096, PhaseHash)
	if p := readProgress(t, key); p.Phase != PhaseHash || p.Progress != 100 {
		t.Fatalf("expected hash 100%%, got %+v", p)
	}

	updateDriveUploadProgressPhase(key, 0, 4096, PhaseUpload)
	p := readProgress(t, key)
	if p.Phase != PhaseUpload {
		t.Errorf("expected phase %q, got %q", PhaseUpload, p.Phase)
	}
	if p.Progress != 0 {
		t.Errorf("expected upload to restart at 0%%, got %f", p.Progress)
	}
}

// updateDriveUploadProgress is the legacy entry point used by progressReader; it must keep
// reporting UPLOAD so the poller never sees an empty phase during the byte transfer.
func TestLegacyProgressUpdateDefaultsToUploadPhase(t *testing.T) {
	key := "/tmp/phase-legacy-test.bin"
	defer clearDriveUploadProgress(key)

	updateDriveUploadProgress(key, 10, 100)
	if p := readProgress(t, key); p.Phase != PhaseUpload {
		t.Errorf("expected phase %q, got %q", PhaseUpload, p.Phase)
	}
}

// Regression for the collision bug: two files with the same basename in different directories
// used to resolve to each other's progress because the lookup matched on HasSuffix in both
// directions. Exact path must win, and a bare-filename query must only match on the basename.
func TestProgressLookupPrefersExactPath(t *testing.T) {
	pathA := "/tmp/dir-a/probe.bin"
	pathB := "/tmp/dir-b/probe.bin"
	clearDriveUploadProgress(pathA)
	clearDriveUploadProgress(pathB)
	defer clearDriveUploadProgress(pathA)
	defer clearDriveUploadProgress(pathB)

	updateDriveUploadProgressPhase(pathA, 111, 1000, PhaseUpload)
	updateDriveUploadProgressPhase(pathB, 222, 2000, PhaseUpload)

	if p := readProgress(t, pathA); p.BytesUploaded != 111 || p.TotalBytes != 1000 {
		t.Errorf("exact path A resolved to the wrong entry: %+v", p)
	}
	if p := readProgress(t, pathB); p.BytesUploaded != 222 || p.TotalBytes != 2000 {
		t.Errorf("exact path B resolved to the wrong entry: %+v", p)
	}
}

// An unknown key must still return a well-formed JSON object. The Kotlin poller parses this
// unconditionally and a bare "" would throw inside the poll loop.
func TestProgressForUnknownKeyIsWellFormed(t *testing.T) {
	raw := GetDriveUploadProgress("/tmp/definitely-not-tracked.bin")
	var p DriveUploadProgress
	if err := json.Unmarshal([]byte(raw), &p); err != nil {
		t.Fatalf("unknown key returned invalid JSON %q: %v", raw, err)
	}
	if p.Phase == "" {
		t.Error("unknown key must still report a phase so the UI can label it")
	}
	if !strings.HasPrefix(raw, "{") {
		t.Errorf("expected a JSON object, got %q", raw)
	}
}

func readProgress(t *testing.T, key string) DriveUploadProgress {
	t.Helper()
	var p DriveUploadProgress
	if err := json.Unmarshal([]byte(GetDriveUploadProgress(key)), &p); err != nil {
		t.Fatalf("invalid progress JSON for %q: %v", key, err)
	}
	return p
}
