package native

// Apple GSA flavour of SRP-6a (2048-bit RFC 5054 group, SHA-256).
//
// Differences from textbook SRP that GSA requires:
//   - x = H(salt | H(":" | P)): the username is NOT part of the identity hash.
//   - u = H(A | B) with A and B unpadded.
//   - K = H(S).
//   - M1 = H(H(N) xor H(PAD(g)) | H(username) | salt | A | B | K).
//   - P is PBKDF2-SHA256(sha256(password) [hex for s2k_fo], salt, iterations, 32).
//
// Verified live by probes/native_ck step 1 (server proof M2 matched).

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"math/big"
)

const srpN2048Hex = "ac6bdb41324a9a9bf166de5e1389582faf72b6651987ee07fc3192943db56050a37329cbb4a099ed8193e0757767a13dd52312ab4b03310dcd7f48a9da04fd50e8083969edb767b0cf6095179a163ab3661a05fbd5faaae82918a9962f0b93b855f97993ec975eeaa80d740adbf4ff747359d041d5c33ea71d281e446b14773bca97b43a23fb801676bd207a436c6481f1d2b9078717461a5b9d32e688f87748544523b524b0d57d5ea77a2775d2ecfa032cfbdbf52fb3786160279004e57ae6af874e7303ce53299ccc041c7bc308d82a5698f3a8d0c38271ae35f8e9dbfbb694b5c803d89f7ae435de236d525f54759b65e372fcd68ef20fa7111f9e4aff73"

var (
	srpN, _ = new(big.Int).SetString(srpN2048Hex, 16)
	srpG    = big.NewInt(2)
)

func sha(parts ...[]byte) []byte {
	d := sha256.New()
	for _, p := range parts {
		d.Write(p)
	}
	return d.Sum(nil)
}

func padTo(b []byte, n int) []byte {
	if len(b) >= n {
		return b
	}
	out := make([]byte, n)
	copy(out[n-len(b):], b)
	return out
}

type srpClient struct {
	a    *big.Int
	APub []byte
}

func newSRPClient() *srpClient {
	a := new(big.Int).SetBytes(randBytes(32))
	A := new(big.Int).Exp(srpG, a, srpN)
	return &srpClient{a: a, APub: A.Bytes()}
}

type srpResult struct {
	M1, M2, Key []byte
}

// pbkdf2SHA256 is single-block PBKDF2 (dkLen = 32 = one SHA-256 block).
func pbkdf2SHA256(password, salt []byte, iter int) []byte {
	mac := hmac.New(sha256.New, password)
	mac.Write(salt)
	mac.Write([]byte{0, 0, 0, 1})
	u := mac.Sum(nil)
	t := append([]byte(nil), u...)
	for i := 1; i < iter; i++ {
		mac.Reset()
		mac.Write(u)
		u = mac.Sum(nil)
		for j := range t {
			t[j] ^= u[j]
		}
	}
	return t
}

func gsaPasswordKey(rawPassword, protocol string, salt []byte, iter int) []byte {
	hashed := sha([]byte(rawPassword))
	in := hashed
	if protocol == "s2k_fo" {
		in = []byte(hex.EncodeToString(hashed))
	}
	return pbkdf2SHA256(in, salt, iter)
}

func (c *srpClient) process(username string, passwordKey, salt, bPub []byte) (*srpResult, error) {
	B := new(big.Int).SetBytes(bPub)
	if new(big.Int).Mod(B, srpN).Sign() == 0 {
		return nil, errors.New("illegal SRP B")
	}
	nBytes := srpN.Bytes()
	A := new(big.Int).SetBytes(c.APub)

	u := new(big.Int).SetBytes(sha(A.Bytes(), B.Bytes()))
	k := new(big.Int).SetBytes(sha(nBytes, padTo(srpG.Bytes(), len(nBytes))))
	x := new(big.Int).SetBytes(sha(salt, sha([]byte(":"), passwordKey)))

	gx := new(big.Int).Exp(srpG, x, srpN)
	kgx := new(big.Int).Mod(new(big.Int).Mul(k, gx), srpN)
	base := new(big.Int).Sub(new(big.Int).Add(B, srpN), kgx)
	base.Mod(base, srpN)
	exp := new(big.Int).Add(c.a, new(big.Int).Mul(u, x))
	S := new(big.Int).Exp(base, exp, srpN)
	key := sha(S.Bytes())

	hn := sha(nBytes)
	hg := sha(padTo(srpG.Bytes(), len(nBytes)))
	for i := range hg {
		hg[i] ^= hn[i]
	}
	m1 := sha(hg, sha([]byte(username)), salt, A.Bytes(), B.Bytes(), key)
	m2 := sha(A.Bytes(), m1, key)
	return &srpResult{M1: m1, M2: m2, Key: key}, nil
}

func (r *srpResult) sessionKey(name string) []byte {
	mac := hmac.New(sha256.New, r.Key)
	mac.Write([]byte(name))
	return mac.Sum(nil)
}

// decryptSPD decrypts the server-provided data blob (AES-256-CBC, PKCS#7).
func (r *srpResult) decryptSPD(data []byte) ([]byte, error) {
	key := r.sessionKey("extra data key:")
	iv := r.sessionKey("extra data iv:")[:16]
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	if len(data)%aes.BlockSize != 0 || len(data) == 0 {
		return nil, errors.New("spd not block aligned")
	}
	out := make([]byte, len(data))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(out, data)
	pad := int(out[len(out)-1])
	if pad == 0 || pad > aes.BlockSize || pad > len(out) {
		return nil, errors.New("bad spd padding")
	}
	return out[:len(out)-pad], nil
}
