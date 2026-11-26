package native

// MMCS put in the V2 ("FORD") format, the only one the Photos zone's final putComplete accepts
// (V1 returns 400 there; measured 2026-11-25).
//
// V2 in short: every 5 MiB chunk gets a random key, its id is an HMAC of its plaintext hash,
// the chunk keys travel in an AES-SIV encrypted "FORD" blob uploaded as one more chunk, and the
// file signature is derived from a boundary key. Apple's clients take that boundary key from
// the iCloud keychain; ours is derived from a per-install secret. The server does not check it,
// and web + iPhone both download and play such uploads byte-identically (measured).

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"google.golang.org/protobuf/proto"

	"icloudcore/native/pb"
)

const chunkSize = 5 * 1024 * 1024

type chunkDesc struct {
	id     [21]byte
	key    [33]byte // 0x04-prefixed random chunk key
	size   int
	offset int64
}

type preparedPut struct {
	totalSig []byte // 0x04 + HMAC(...)[:20]; becomes fileChecksum / CPLMaster recordName
	totalLen int64
	chunks   []chunkDesc
	fordSig  []byte // 0x01 + sha1(fordKey); becomes referenceChecksum
	fordData []byte // encrypted FORD blob
	fordKey  []byte // becomes wrappingKey
}

// boundaryKey derives the per-install V2 boundary key.
func boundaryKey(secretB64 string) ([]byte, error) {
	secret, err := base64.StdEncoding.DecodeString(secretB64)
	if err != nil || len(secret) < 16 {
		return nil, errors.New("bad boundary secret")
	}
	return hkdfExpand(nil, secret, "iclouddroid v2 boundary key", 32), nil
}

// fileSignatureV2 is the part of V2 preparation that depends only on the bytes and the boundary
// key, so it can be computed for the duplicate check before anything is uploaded.
func fileSignatureV2(hash, bk []byte) (sig, checksum []byte) {
	fileKey := hkdfExpand(nil, bk, "file-key", 0x20)
	checksum = hkdfExpand(nil, fileKey, string(hash), 0x20)
	mac := hmac256(checksum, hash)
	return append([]byte{0x04}, mac[:20]...), checksum
}

// preparePutV2 reads r once: chunk ids, chunk keys, file signature and the FORD blob.
func preparePutV2(r io.ReaderAt, size int64, bk []byte) (*preparedPut, error) {
	total := sha256.New()
	total.Write([]byte("com.apple.DataObjectSaltV2"))
	pp := &preparedPut{}
	var fordChunks []*pb.FordChunkItem
	buf := make([]byte, chunkSize)
	for off := int64(0); off < size; {
		n := chunkSize
		if rem := size - off; rem < int64(n) {
			n = int(rem)
		}
		if _, err := r.ReadAt(buf[:n], off); err != nil && !(err == io.EOF && n > 0) {
			return nil, fmt.Errorf("read at %d: %w", off, err)
		}
		data := buf[:n]
		total.Write(data)
		var key [33]byte
		copy(key[:], randBytes(33))
		key[0] = 0x04
		exp := hkdfExpand(nil, key[1:], "signature-key", 0x60)
		ph := sha256.Sum256(data)
		mac := hmac256(exp[0x00:0x20], ph[:])
		var id [21]byte
		id[0] = 0x84
		copy(id[1:], mac[:20])
		var lenLE [4]byte
		binary.LittleEndian.PutUint32(lenLE[:], uint32(n))
		fordChunks = append(fordChunks, &pb.FordChunkItem{Key: append([]byte{}, key[:]...), ChunkLen: lenLE[:]})
		pp.chunks = append(pp.chunks, chunkDesc{id: id, key: key, size: n, offset: off})
		off += int64(n)
	}
	pp.totalLen = size
	sig, checksum := fileSignatureV2(total.Sum(nil), bk)
	pp.totalSig = sig

	fordPlain, err := proto.Marshal(&pb.FordChunk{Item: &pb.FordItem{Chunks: fordChunks, Checksum: checksum}})
	if err != nil {
		return nil, err
	}
	fordKey := randBytes(32)
	sivKey := hkdfExpand([]byte("PCSMMCS2"), fordKey, "", 64)
	iv := randBytes(16)
	enc := sivEncrypt(sivKey, [][]byte{iv, {0x04}}, fordPlain)
	pp.fordData = append(append([]byte{0x04}, iv...), enc...)
	fs := sha1.Sum(fordKey)
	pp.fordSig = append([]byte{0x01}, fs[:]...)
	pp.fordKey = fordKey
	return pp, nil
}

func (pp *preparedPut) authorizePut() *pb.AuthorizePut {
	chunks := make([]*pb.AuthorizePut_PutData_Chunk, len(pp.chunks))
	for i, c := range pp.chunks {
		chunks[i] = &pb.AuthorizePut_PutData_Chunk{Sig: append([]byte{}, c.id[:]...), Size: uint32(c.size)}
	}
	return &pb.AuthorizePut{
		Data: []*pb.AuthorizePut_PutData{{
			Sig:    pp.totalSig,
			Token:  proto.String(""), // present-but-empty: the token rides in x-apple-mmcs-auth
			Chunks: chunks,
			Footer: &pb.AuthorizePut_PutData_Footer{
				ChunkCount:  uint32(len(pp.chunks)),
				ProfileType: "kCKProfileTypeFixed",
				F103:        proto.Uint32(0),
				F102:        proto.String("2022-08-11"),
				F104:        proto.String("fxd"),
			},
			FordSig:  pp.fordSig,
			FordDesc: &pb.AuthorizePut_PutData_FordDesc{Len: uint32(len(pp.fordData))},
		}},
		F3: 81,
	}
}

// encryptChunkV2 is AES-256-CTR; key and IV are derived from the chunk key and chunk id.
func encryptChunkV2(c *chunkDesc, data []byte) []byte {
	exp := hkdfExpand(nil, c.key[1:], "signature-key", 0x60)
	id := make([]byte, 40)
	copy(id, c.id[1:])
	binary.LittleEndian.PutUint32(id[32:36], uint32(len(data)))
	h := hmac256(exp[0x20:0x40], id)
	block, err := aes.NewCipher(exp[0x40:0x60])
	if err != nil {
		panic(err)
	}
	out := make([]byte, len(data))
	cipher.NewCTR(block, h[:16]).XORKeyStream(out, data)
	return out
}

func fordIndexID(idx uint32) [21]byte {
	var id [21]byte
	id[0] = 0x7f
	binary.LittleEndian.PutUint32(id[1:5], idx)
	return id
}

type mmcsTarget struct {
	method  string
	url     string
	headers map[string]string
	edge    bool // put-complete-at-edge: the receipt comes back in the PUT body
	chunks  [][21]byte
	authP1  string
	authP2  string
}

func (s *ckSession) mmcsAuthorizePut(pp *preparedPut, tok *assetToken) (*pb.AuthorizePutResponse, error) {
	body, err := proto.Marshal(pp.authorizePut())
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequest("POST", tok.host+"/"+tok.container+"/authorizePut", bytes.NewReader(body))
	s.applyMMCSHeaders(req, tok.dsid, fmt.Sprintf("%x %d %s", pp.totalSig, pp.totalLen, tok.token))
	resp, err := httpClient(60 * time.Second).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	raw = maybeGunzip(raw)
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("authorizePut HTTP %d (%s): %.120q", resp.StatusCode, resp.Header.Get("x-apple-mmcs-error"), raw)
	}
	var out pb.AuthorizePutResponse
	if err := proto.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("authorizePut reply: %w", err)
	}
	return &out, nil
}

func parseTargets(r *pb.AuthorizePutResponse) []mmcsTarget {
	var out []mmcsTarget
	for _, t := range r.GetTargets() {
		hr := t.GetRequest()
		if hr == nil {
			continue
		}
		port := hr.GetPort()
		if port == 0 {
			port = 443
		}
		tgt := mmcsTarget{
			method:  hr.GetMethod(),
			url:     fmt.Sprintf("%s://%s:%d%s", hr.GetScheme(), hr.GetDomain(), port, hr.GetPath()),
			headers: map[string]string{},
			authP1:  t.GetClAuthP1(),
			authP2:  t.GetClAuthP2(),
		}
		for _, h := range hr.GetHeaders() {
			tgt.headers[h.GetName()] = h.GetValue()
			if h.GetName() == "x-apple-put-complete-at-edge-version" && h.GetValue() == "2" {
				tgt.edge = true
			}
		}
		for _, c := range t.GetChunks() {
			if len(c.GetChunkId()) > 0 {
				var id [21]byte
				copy(id[:], c.GetChunkId())
				tgt.chunks = append(tgt.chunks, id)
			} else if c.FordIndex != nil {
				tgt.chunks = append(tgt.chunks, fordIndexID(c.GetFordIndex()))
			}
		}
		out = append(out, tgt)
	}
	return out
}

type putResult struct {
	status   int
	etag     string
	edgeInfo string
	md5      []byte
	length   int64
	body     []byte
}

// putTarget sends one bucket: its chunks encrypted, in the order the target lists them, plus a
// footer for edge-completed targets. A bucket is ~30 MiB, so it is assembled in memory.
func putTarget(tgt mmcsTarget, pp *preparedPut, r io.ReaderAt, confirmData []byte) (*putResult, error) {
	byID := make(map[[21]byte]*chunkDesc, len(pp.chunks))
	for i := range pp.chunks {
		byID[pp.chunks[i].id] = &pp.chunks[i]
	}
	hasher := md5.New()
	var body bytes.Buffer
	for _, id := range tgt.chunks {
		if id[0] == 0x7f {
			hasher.Write(pp.fordData)
			body.Write(pp.fordData)
			continue
		}
		c, ok := byID[id]
		if !ok {
			return nil, fmt.Errorf("target asks for an unknown chunk")
		}
		raw := make([]byte, c.size)
		if _, err := r.ReadAt(raw, c.offset); err != nil && !(err == io.EOF) {
			return nil, err
		}
		enc := encryptChunkV2(c, raw)
		hasher.Write(enc)
		body.Write(enc)
	}
	sum := hasher.Sum(nil)
	sent := int64(body.Len())
	payload := body.Bytes()
	if tgt.edge {
		footer, err := proto.Marshal(&pb.PutFooter{Md5Sum: sum, ConfirmData: confirmData})
		if err != nil {
			return nil, err
		}
		var lb [4]byte
		binary.BigEndian.PutUint32(lb[:], uint32(len(footer)))
		payload = append(payload, lb[:]...)
		payload = append(payload, footer...)
	}

	req, _ := http.NewRequest(tgt.method, tgt.url, bytes.NewReader(payload))
	req.Header.Set("x-apple-request-uuid", NewUUID())
	req.Header.Set("user-agent", "IMTransferAgent/1000 CFNetwork/1494.0.7 Darwin/23.4.0")
	for name, val := range tgt.headers {
		switch {
		case name == "Host":
			// The presigned OSS URL signs the bare host; Go would send "host:443" -> 403.
			req.Host = val
		case name == "Content-Length" && tgt.edge:
		default:
			req.Header.Set(name, val)
		}
	}
	resp, err := httpClient(10 * time.Minute).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	sample, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	return &putResult{
		status: resp.StatusCode, etag: resp.Header.Get("ETag"), edgeInfo: resp.Header.Get("x-apple-edge-info"),
		md5: sum, length: sent, body: maybeGunzip(sample),
	}, nil
}

func (s *ckSession) putComplete(tok *assetToken, tgt mmcsTarget, pr *putResult, confirmData []byte) (map[string]string, error) {
	var edge []*pb.ConfirmResponse_Request_Metric
	if pr.etag != "" {
		edge = append(edge, &pb.ConfirmResponse_Request_Metric{N: "Etag", V: pr.etag})
	}
	if pr.edgeInfo != "" {
		edge = append(edge, &pb.ConfirmResponse_Request_Metric{N: "x-apple-edge-info", V: pr.edgeInfo})
	}
	body, err := proto.Marshal(&pb.ConfirmResponse{
		Inner: []*pb.ConfirmResponse_Request{{
			Url:       tgt.url,
			Status:    uint32(pr.status),
			EdgeInfo:  edge,
			UploadMd5: pr.md5,
			Token:     tgt.authP2,
		}},
		ConfirmData: confirmData,
	})
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequest("POST", tok.host+"/"+tok.container+"/putComplete", bytes.NewReader(body))
	s.applyMMCSHeaders(req, tok.dsid, fmt.Sprintf("%s %d %s", tgt.authP1, pr.length, tgt.authP2))
	resp, err := httpClient(60 * time.Second).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("putComplete HTTP %d", resp.StatusCode)
	}
	return parsePutComplete(maybeGunzip(raw))
}

func parsePutComplete(raw []byte) (map[string]string, error) {
	var m pb.PutCompleteResponse
	if err := proto.Unmarshal(raw, &m); err != nil {
		return nil, err
	}
	out := map[string]string{}
	for _, f := range m.GetFinished() {
		if f.GetReceipt() != "" {
			out[b64(f.GetSignature())] = f.GetReceipt()
		}
	}
	return out, nil
}

func isRetryable(status int) bool {
	return status == 408 || status == 429 || status/100 == 5
}
