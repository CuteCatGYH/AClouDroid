package native

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"math/big"
	"net"
	"strings"
	"testing"
	"time"
)

func unhex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

// RFC 5297 appendix A.1 (deterministic AES-SIV).
func TestSIVVector(t *testing.T) {
	key := unhex("fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
	ad := unhex("101112131415161718191a1b1c1d1e1f2021222324252627")
	p := unhex("112233445566778899aabbccddee")
	got := hex.EncodeToString(sivEncrypt(key, [][]byte{ad}, p))
	if want := "85632d07c6e8f37f950acd320a2ecc9340c02b9690c4dc04daef7f6afe5c"; got != want {
		t.Fatalf("siv = %s, want %s", got, want)
	}
}

// RFC 4493 examples 1 and 2.
func TestCMACVector(t *testing.T) {
	key := unhex("2b7e151628aed2a6abf7158809cf4f3c")
	if got := hex.EncodeToString(cmac(key, unhex("6bc1bee22e409f96e93d7e117393172a"))); got != "070a16b46b4d4144f79bdd9dd04a287c" {
		t.Fatalf("cmac = %s", got)
	}
	if hex.EncodeToString(cmac(key, nil)) != "bb1d6929e95937287fa37d129b756746" {
		t.Fatal("cmac empty mismatch")
	}
}

// Plays the server side of GSA SRP locally; both sides must derive the same key.
func TestSRPRoundTrip(t *testing.T) {
	salt := randBytes(16)
	pk := gsaPasswordKey("correct horse", "s2k", salt, 1000)
	nBytes := srpN.Bytes()
	k := new(big.Int).SetBytes(sha(nBytes, padTo(srpG.Bytes(), len(nBytes))))
	x := new(big.Int).SetBytes(sha(salt, sha([]byte(":"), pk)))
	v := new(big.Int).Exp(srpG, x, srpN)
	b := new(big.Int).SetBytes(randBytes(32))
	B := new(big.Int).Add(new(big.Int).Mul(k, v), new(big.Int).Exp(srpG, b, srpN))
	B.Mod(B, srpN)

	c := newSRPClient()
	res, err := c.process("user@example.com", pk, salt, B.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	A := new(big.Int).SetBytes(c.APub)
	u := new(big.Int).SetBytes(sha(A.Bytes(), B.Bytes()))
	S := new(big.Int).Exp(new(big.Int).Mul(A, new(big.Int).Exp(v, u, srpN)), b, srpN)
	if !bytes.Equal(sha(S.Bytes()), res.Key) {
		t.Fatal("client and server keys differ")
	}
	bad, _ := c.process("user@example.com", gsaPasswordKey("wrong", "s2k", salt, 1000), salt, B.Bytes())
	if bytes.Equal(bad.Key, res.Key) {
		t.Fatal("wrong password produced the same key")
	}
}

func TestPBKDF2Vector(t *testing.T) {
	got := hex.EncodeToString(pbkdf2SHA256([]byte("password"), []byte("salt"), 4096))
	if want := "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"; got != want {
		t.Fatalf("pbkdf2 = %s, want %s", got, want)
	}
}

func TestDeviceInfo(t *testing.T) {
	full := "<Mac16,10> <macOS;26.7.3;25G342> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
	if got := deviceInfoPrefix(full); got != "<Mac16,10> <macOS;26.7.3;25G342>" {
		t.Fatalf("prefix %q", got)
	}
	if hw, sw := deviceVersions(full); hw != "Mac16,10" || sw != "26.7.3" {
		t.Fatalf("versions %q %q", hw, sw)
	}
}

func TestClientInfoFor(t *testing.T) {
	mac := Anisette{"X-Mme-Client-Info": "<MacBookPro13,3> <Mac OS X;10.12.6;16G29> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"}
	if got := clientInfoFor(mac, "<x/1 (y/1)>"); got != "<MacBookPro13,3> <Mac OS X;10.12.6;16G29> <x/1 (y/1)>" {
		t.Fatalf("mac %q", got)
	}
	win := "<PC> <Windows; 6.2.9200.9974; Win8 Core; x64> <com.apple.AOSKit/133.3 (com.apple.iCloud/15.10)>"
	if got := clientInfoFor(Anisette{"X-Mme-Client-Info": win}, "<x/1 (y/1)>"); got != win {
		t.Fatalf("windows must be verbatim, got %q", got)
	}
	if hw, sw := deviceVersions(win); hw != "PC" || sw != "6.2.9200.9974" {
		t.Fatalf("windows versions %q %q", hw, sw)
	}
}

// The file signature must depend on the bytes and the install's boundary key only, never on
// the random chunk keys: that is what makes the pre-upload duplicate lookup work.
func TestV2SignatureDeterministic(t *testing.T) {
	data := bytes.NewReader(randBytes(chunkSize*2 + 12345))
	bk1, _ := boundaryKey(b64(randBytes(32)))
	bk2, _ := boundaryKey(b64(randBytes(32)))
	size := int64(data.Len())

	a, err := preparePutV2(data, size, bk1)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := preparePutV2(data, size, bk1)
	c, _ := preparePutV2(data, size, bk2)

	if !bytes.Equal(a.totalSig, b.totalSig) {
		t.Fatal("same bytes + same key gave different signatures")
	}
	if bytes.Equal(a.totalSig, c.totalSig) {
		t.Fatal("different boundary keys gave the same signature")
	}
	if len(a.totalSig) != 21 || a.totalSig[0] != 0x04 {
		t.Fatalf("signature shape %x", a.totalSig)
	}
	if len(a.chunks) != 3 || a.chunks[2].size != 12345 || a.chunks[2].offset != chunkSize*2 {
		t.Fatalf("chunking wrong: %+v", len(a.chunks))
	}
	if a.chunks[0].id == b.chunks[0].id {
		t.Fatal("chunk ids should differ between preparations (random chunk keys)")
	}
	if len(a.fordSig) != 21 || a.fordSig[0] != 0x01 || len(a.fordKey) != 32 {
		t.Fatal("ford fields wrong")
	}
}

// Chunk encryption is a pure keystream: encrypting twice restores the plaintext.
func TestEncryptChunkV2Involution(t *testing.T) {
	data := bytes.NewReader(randBytes(1000))
	bk, _ := boundaryKey(b64(randBytes(32)))
	pp, _ := preparePutV2(data, 1000, bk)
	plain := make([]byte, 1000)
	data.ReadAt(plain, 0)
	enc := encryptChunkV2(&pp.chunks[0], plain)
	if bytes.Equal(enc, plain) {
		t.Fatal("not encrypted")
	}
	if !bytes.Equal(encryptChunkV2(&pp.chunks[0], enc), plain) {
		t.Fatal("CTR round trip failed")
	}
}

func TestUleb128(t *testing.T) {
	for _, n := range []uint64{0, 1, 127, 128, 300, 1 << 35} {
		got, err := readUleb128(bytes.NewReader(uleb128(n)))
		if err != nil || got != n {
			t.Fatalf("%d -> %d %v", n, got, err)
		}
	}
}

func TestCanonicalAnisetteKey(t *testing.T) {
	if canonicalAnisetteKey("X-MMe-Client-Info") != "X-Mme-Client-Info" || canonicalAnisetteKey("x-apple-i-md") != "X-Apple-I-MD" {
		t.Fatal("anisette key folding")
	}
}

// Android lacks Apple's private root, so the embedded bundle alone must verify gsa.apple.com
// (and the icloud.com hosts). Uses ONLY the embedded roots, not this Mac's keychain.
func TestEmbeddedAppleRootsVerifyLiveHosts(t *testing.T) {
	if testing.Short() {
		t.Skip("network")
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(appleRootsPEM) {
		t.Fatal("embedded roots do not parse")
	}
	for _, host := range []string{"gsa.apple.com", "setup.icloud.com", "gateway.icloud.com"} {
		conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 10 * time.Second}, "tcp", host+":443",
			&tls.Config{ServerName: host, RootCAs: pool})
		if err != nil {
			if strings.Contains(err.Error(), "certificate") {
				t.Fatalf("%s: %v", host, err)
			}
			t.Skipf("no network: %v", err)
		}
		conn.Close()
	}
}
