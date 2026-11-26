package native

// High-level entry: upload one file's bytes into the Photos zone and return the same receipt
// shape the web `/assets/upload` path produces, so the caller's record-building code is shared.

import (
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"google.golang.org/protobuf/proto"

	"icloudcore/native/pb"
)

// Receipt mirrors go-core's mmcsReceipt (the `singleFile` object of a web upload).
type Receipt struct {
	FileChecksum      string
	ReferenceChecksum string
	WrappingKey       string
	Receipt           string
	Size              int64
}

// ErrTooLarge means even the native path hit a body-size gate (has never been observed).
var ErrTooLarge = errors.New("native upload rejected with 413")

type PhotosUploader struct {
	s  *ckSession
	bk []byte
}

// NewPhotosUploader fetches fresh anisette, re-derives cloudKitToken and opens the Photos zone.
func NewPhotosUploader(anisetteURL string, creds *Credentials) (*PhotosUploader, error) {
	if !creds.Valid() {
		return nil, errors.New("not logged in for native upload")
	}
	bk, err := boundaryKey(creds.BoundarySecret)
	if err != nil {
		return nil, err
	}
	ani, err := FetchAnisette(anisetteURL)
	if err != nil {
		return nil, err
	}
	s, err := openPhotos(ani, creds)
	if err != nil {
		return nil, err
	}
	return &PhotosUploader{s: s, bk: bk}, nil
}

// Prepared is one file chunked and signed, ready to upload.
type Prepared struct{ pp *preparedPut }

// FileChecksum is what the CPLMaster recordName will be. Deterministic per install.
func (p *Prepared) FileChecksum() string { return b64(p.pp.totalSig) }

func (u *PhotosUploader) Prepare(r io.ReaderAt, size int64) (*Prepared, error) {
	if size <= 0 {
		return nil, errors.New("empty file")
	}
	pp, err := preparePutV2(r, size, u.bk)
	if err != nil {
		return nil, err
	}
	return &Prepared{pp: pp}, nil
}

// Upload sends the bytes and returns the receipt. progress may be nil.
func (u *PhotosUploader) Upload(p *Prepared, r io.ReaderAt, progress func(sent, total int64)) (*Receipt, error) {
	pp := p.pp
	s := u.s
	recordName := "iclouddroid-" + strings.ToLower(NewUUID())
	tok, err := s.assetUploadToken(pp, recordName, "CPLMaster", "resOriginalRes")
	if err != nil {
		return nil, err
	}
	logf("upload target host=%s chunks=%d size=%d embedded=%v", hostOnly(tok.host), len(pp.chunks), pp.totalLen, len(tok.uploadInfo) > 0)
	var ap *pb.AuthorizePutResponse
	if len(tok.uploadInfo) > 0 {
		ap = &pb.AuthorizePutResponse{}
		if err := proto.Unmarshal(tok.uploadInfo, ap); err != nil {
			return nil, fmt.Errorf("embedded uploadInfo: %w", err)
		}
	} else if ap, err = s.mmcsAuthorizePut(pp, tok); err != nil {
		return nil, err
	}
	receipts := map[string]string{}
	for _, st := range ap.GetCurrentStates() {
		if st.GetReceipt() != "" {
			receipts[b64(st.GetSignature())] = st.GetReceipt()
		}
	}
	targets := parseTargets(ap)
	if len(targets) == 0 && len(receipts) == 0 {
		return nil, errors.New("authorizePut returned no upload targets")
	}
	confirm := ap.GetConfirmData()

	start := time.Now()
	var sent int64
	for i, tgt := range targets {
		var pr *putResult
		for attempt := 1; ; attempt++ {
			pr, err = putTarget(tgt, pp, r, confirm)
			if err == nil && !isRetryable(pr.status) {
				break
			}
			if attempt >= 3 {
				if err != nil {
					return nil, fmt.Errorf("bucket %d/%d: %w", i+1, len(targets), err)
				}
				return nil, fmt.Errorf("bucket %d/%d: HTTP %d", i+1, len(targets), pr.status)
			}
			time.Sleep(time.Duration(attempt*2) * time.Second)
		}
		if pr.status == 413 {
			return nil, ErrTooLarge
		}
		if pr.status/100 != 2 {
			return nil, fmt.Errorf("bucket %d/%d: HTTP %d", i+1, len(targets), pr.status)
		}
		sent += pr.length
		if progress != nil {
			// sent includes the small FORD blob; never report past 100%.
			progress(min(sent, pp.totalLen), pp.totalLen)
		}
		var got map[string]string
		if tgt.edge {
			if len(pr.body) > 0 {
				got, _ = parsePutComplete(pr.body)
			}
		} else if got, err = s.putComplete(tok, tgt, pr, confirm); err != nil {
			return nil, fmt.Errorf("bucket %d/%d: %w", i+1, len(targets), err)
		}
		for k, v := range got {
			receipts[k] = v
		}
	}
	receipt := receipts[b64(pp.totalSig)]
	if receipt == "" {
		return nil, errors.New("upload finished without a receipt for the file")
	}
	logf("native upload ok: %d buckets, %d bytes in %s", len(targets), sent, time.Since(start).Round(time.Second))
	return &Receipt{
		FileChecksum:      b64(pp.totalSig),
		ReferenceChecksum: b64(pp.fordSig),
		WrappingKey:       b64(pp.fordKey),
		Receipt:           receipt,
		Size:              pp.totalLen,
	}, nil
}

func hostOnly(u string) string {
	if i := strings.Index(u, "//"); i >= 0 {
		u = u[i+2:]
	}
	if j := strings.IndexByte(u, '/'); j >= 0 {
		u = u[:j]
	}
	return u
}
