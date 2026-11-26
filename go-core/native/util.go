// Package native talks to iCloud the way Apple's own clients do (GrandSlam login, CloudKit
// protobuf API, MMCS chunked upload). It exists for one reason: the web upload path sends the
// whole file as a single POST and the 云上贵州 gateway rejects bodies over 1 GiB with 413, while
// MMCS splits the file into ~30 MiB bucket PUTs that never reach that limit.
//
// Every protocol detail here was measured against the live service by probes/native_ck; see
// probes/native_ck/NOTES.md for the evidence behind each choice. Written from the protocol; no
// third-party code copied.
package native

import (
	"bytes"
	"compress/gzip"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"strings"
)

func logf(format string, a ...interface{}) {
	log.Printf("[native] "+format, a...)
}

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

func randBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

// NewUUID returns an upper-case random (v4) UUID, the form Apple's daemons send.
func NewUUID() string {
	b := randBytes(16)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return strings.ToUpper(fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:]))
}

func randHexID() string { return strings.ToUpper(hex.EncodeToString(randBytes(8))) }

func uleb128(n uint64) []byte {
	var out []byte
	for {
		b := byte(n & 0x7f)
		n >>= 7
		if n != 0 {
			b |= 0x80
		}
		out = append(out, b)
		if n == 0 {
			return out
		}
	}
}

func readUleb128(r *bytes.Reader) (uint64, error) {
	var result uint64
	var shift uint
	for {
		b, err := r.ReadByte()
		if err != nil {
			return 0, err
		}
		result |= uint64(b&0x7f) << shift
		if b&0x80 == 0 {
			return result, nil
		}
		shift += 7
		if shift > 63 {
			return 0, fmt.Errorf("uleb128 overflow")
		}
	}
}

func gzipBytes(b []byte) []byte {
	var buf bytes.Buffer
	w := gzip.NewWriter(&buf)
	w.Write(b)
	w.Close()
	return buf.Bytes()
}

// maybeGunzip returns b decompressed when it is gzip, otherwise b unchanged. Apple sends gzip
// whether or not Content-Encoding says so.
func maybeGunzip(b []byte) []byte {
	if len(b) < 2 || b[0] != 0x1f || b[1] != 0x8b {
		return b
	}
	r, err := gzip.NewReader(bytes.NewReader(b))
	if err != nil {
		return b
	}
	defer r.Close()
	out, err := io.ReadAll(r)
	if err != nil {
		return b
	}
	return out
}

func short(s string) string {
	if len(s) <= 6 {
		return s
	}
	return s[:6]
}

func toInt(v interface{}) int64 {
	switch n := v.(type) {
	case int64:
		return n
	case uint64:
		return int64(n)
	case int:
		return int64(n)
	case float64:
		return int64(n)
	}
	return 0
}
