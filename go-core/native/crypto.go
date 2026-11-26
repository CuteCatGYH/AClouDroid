package native

// Crypto primitives of the MMCS V2 ("FORD") upload format: HKDF-SHA256, HMAC-SHA256, AES-CTR
// chunk encryption, and AES-SIV (RFC 5297, built on AES-CMAC RFC 4493) for the FORD blob.
// Verified against the RFC test vectors in crypto_test.go.

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
)

func hkdfExpand(salt, ikm []byte, info string, n int) []byte {
	out, err := hkdf.Key(sha256.New, ikm, salt, info, n)
	if err != nil {
		panic(err)
	}
	return out
}

func hmac256(key, msg []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(msg)
	return m.Sum(nil)
}

func dbl(b []byte) []byte {
	out := make([]byte, 16)
	var carry byte
	for i := 15; i >= 0; i-- {
		out[i] = b[i]<<1 | carry
		carry = b[i] >> 7
	}
	if carry != 0 {
		out[15] ^= 0x87
	}
	return out
}

func xorInto(dst, src []byte) {
	for i := range dst {
		dst[i] ^= src[i]
	}
}

func cmac(key, msg []byte) []byte {
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	l := make([]byte, 16)
	block.Encrypt(l, make([]byte, 16))
	k1 := dbl(l)
	k2 := dbl(k1)
	n := (len(msg) + 15) / 16
	complete := n > 0 && len(msg)%16 == 0
	if n == 0 {
		n = 1
	}
	last := make([]byte, 16)
	if complete {
		copy(last, msg[(n-1)*16:])
		xorInto(last, k1)
	} else {
		rem := msg[(n-1)*16:]
		copy(last, rem)
		last[len(rem)] = 0x80
		xorInto(last, k2)
	}
	x := make([]byte, 16)
	for i := 0; i < n-1; i++ {
		xorInto(x, msg[i*16:(i+1)*16])
		block.Encrypt(x, x)
	}
	xorInto(x, last)
	block.Encrypt(x, x)
	return x
}

func s2v(key []byte, ad [][]byte, p []byte) []byte {
	d := cmac(key, make([]byte, 16))
	for _, a := range ad {
		d = dbl(d)
		xorInto(d, cmac(key, a))
	}
	var t []byte
	if len(p) >= 16 {
		t = append([]byte{}, p...)
		xorInto(t[len(t)-16:], d)
	} else {
		pad := make([]byte, 16)
		copy(pad, p)
		pad[len(p)] = 0x80
		t = dbl(d)
		xorInto(t, pad)
	}
	return cmac(key, t)
}

// sivEncrypt returns V || C. The key is split in half: S2V key, then CTR key (RFC 5297).
func sivEncrypt(key []byte, ad [][]byte, p []byte) []byte {
	h := len(key) / 2
	v := s2v(key[:h], ad, p)
	q := append([]byte{}, v...)
	q[8] &= 0x7f
	q[12] &= 0x7f
	block, err := aes.NewCipher(key[h:])
	if err != nil {
		panic(err)
	}
	c := make([]byte, len(p))
	cipher.NewCTR(block, q).XORKeyStream(c, p)
	return append(v, c...)
}
