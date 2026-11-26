package native

// CloudKit native API: ckAppInit (container -> cloudKitUserId) and the protobuf
// AssetUploadTokenRetrieve call that authorises one MMCS put.

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"google.golang.org/protobuf/proto"

	"icloudcore/native/pb"
)

const (
	PhotosContainer = "com.apple.photos.cloud"
	PhotosBundle    = "com.apple.cloudphotod"
	PhotosZone      = "PrimarySync"

	ckUserAgent   = "CloudKit/1970 (19H384)"
	mmcsPlistSHA  = "fvj0Y/Ybu1pq0r4NxXw3eP51exujUkEAd7LllbkTdK8="
	ckGatewayBase = "https://gateway.icloud.com"
)

// ErrAuthExpired means mmeAuthToken no longer works and the user must log in again.
var ErrAuthExpired = errors.New("native credentials expired; log in again")

// ckSession is everything one upload needs, derived fresh from Credentials + anisette.
type ckSession struct {
	ani         Anisette
	creds       *Credentials
	container   string
	bundle      string
	zone        string
	userID      string
	cloudKitTok string
	hwVersion   string
	swVersion   string
	opGroup     string
	op          string
}

func openPhotos(ani Anisette, creds *Credentials) (*ckSession, error) {
	t, status, err := accountSettings(ani, creds.DSID, creds.MMEToken)
	if err != nil {
		if status == 401 {
			return nil, ErrAuthExpired
		}
		return nil, err
	}
	ckTok := t.Tokens["cloudKitToken"]
	if ckTok == "" {
		return nil, errors.New("account settings returned no cloudKitToken")
	}
	hw, sw := deviceVersions(ani["X-Mme-Client-Info"])
	s := &ckSession{
		ani: ani, creds: creds, container: PhotosContainer, bundle: PhotosBundle, zone: PhotosZone,
		cloudKitTok: ckTok, hwVersion: hw, swVersion: sw,
		opGroup: randHexID(), op: randHexID(),
	}
	uid, err := s.appInit()
	if err != nil {
		return nil, err
	}
	s.userID = uid
	return s, nil
}

func (s *ckSession) clientInfo() string {
	return clientInfoFor(s.ani, "<com.apple.cloudkit.CloudKitDaemon/1970 (com.apple.cloudd/1970)>")
}

func (s *ckSession) appInit() (string, error) {
	u := ckGatewayBase + "/setup/setup/ck/v1/ckAppInit?container=" + url.QueryEscape(s.container)
	req, _ := http.NewRequest("POST", u, nil)
	for k, v := range s.ani.pick(mdKeys...) {
		req.Header.Set(k, v)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", ckUserAgent)
	req.Header.Set("X-CloudKit-BundleId", s.bundle)
	req.Header.Set("X-CloudKit-ContainerId", s.container)
	req.Header.Set("X-CloudKit-DatabaseScope", "Private")
	req.Header.Set("X-CloudKit-Environment", "Production")
	req.Header.Set("X-Mme-Client-Info", s.clientInfo())
	req.Header.Set("X-Apple-Request-UUID", NewUUID())
	req.SetBasicAuth(s.creds.DSID, s.creds.MMEToken)
	resp, err := httpClient(30 * time.Second).Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode == 401 {
		return "", ErrAuthExpired
	}
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("ckAppInit HTTP %d", resp.StatusCode)
	}
	var m struct {
		UserID string `json:"cloudKitUserId"`
	}
	if json.Unmarshal(raw, &m) != nil || m.UserID == "" {
		return "", errors.New("ckAppInit returned no cloudKitUserId")
	}
	return m.UserID, nil
}

func (s *ckSession) requestHeader() *pb.RequestOperation_Header {
	return &pb.RequestOperation_Header{
		ApplicationContainer:            proto.String(s.container),
		ApplicationBundle:               proto.String(s.bundle),
		DeviceIdentifier:                &pb.Identifier{Name: proto.String(s.creds.DeviceUUID), Type: pb.Identifier_DEVICE.Enum()},
		DeviceSoftwareVersion:           proto.String(s.swVersion),
		DeviceHardwareVersion:           proto.String(s.hwVersion),
		DeviceLibraryName:               proto.String("com.apple.cloudkit.CloudKitDaemon"),
		DeviceLibraryVersion:            proto.String("1970"),
		DeviceFlowControlKey:            proto.String("CKDModifyRecordsOperation-Private"),
		DeviceFlowControlBudget:         proto.Uint64(0),
		DeviceFlowControlBudgetCap:      proto.Uint64(0),
		DeviceFlowControlRegeneration:   proto.Float32(0),
		MmcsProtocolVersion:             proto.String("5.0"),
		ApplicationContainerEnvironment: pb.RequestOperation_Header_PRODUCTION.Enum(),
		DeviceAssignedName:              proto.String("Mac"),
		DeviceHardwareID:                proto.String(s.creds.HardwareID),
		TargetDatabase:                  pb.RequestOperation_Header_PRIVATE_DB.Enum(),
		IsolationLevel:                  pb.RequestOperation_Header_ZONE.Enum(),
		Group:                           proto.String("EphemeralGroup"),
		Unk1:                            proto.Int32(0),
		Unk2:                            proto.String(hex.EncodeToString(sha1sum([]byte(s.creds.DeviceUUID)))),
		DeviceSerial:                    proto.String(s.ani["X-Apple-SRL-NO"]),
		Unk3:                            proto.Int32(0),
		Unk4:                            proto.Int32(1),
	}
}

func sha1sum(b []byte) []byte { h := sha1.Sum(b); return h[:] }

func (s *ckSession) recordID(name string) *pb.RecordIdentifier {
	return &pb.RecordIdentifier{
		Value: &pb.Identifier{Name: proto.String(name), Type: pb.Identifier_RECORD.Enum()},
		ZoneIdentifier: &pb.RecordZoneIdentifier{
			Value:           &pb.Identifier{Name: proto.String(s.zone), Type: pb.Identifier_RECORD_ZONE.Enum()},
			OwnerIdentifier: &pb.Identifier{Name: proto.String(s.userID), Type: pb.Identifier_USER.Enum()},
		},
	}
}

type assetToken struct {
	host      string
	container string
	dsid      string
	token     string // encodedInnerProto: the per-asset MMCS put token
	// uploadInfo is an AuthorizePutResponse CloudKit embeds when it already authorised the put.
	// When present, a separate authorizePut call is not only unnecessary but answers 401.
	uploadInfo []byte
}

func (s *ckSession) assetUploadToken(pp *preparedPut, recordName, recordType, field string) (*assetToken, error) {
	authPut, err := proto.Marshal(pp.authorizePut())
	if err != nil {
		return nil, err
	}
	rid := s.recordID(recordName)
	data := &pb.AssetUploadData{Sig: pp.totalSig, Size: proto.Uint64(uint64(pp.totalLen)), AssociatedRecord: rid, FordSig: pp.fordSig}
	opUUID := NewUUID()
	reqOp := &pb.RequestOperation{
		Header: s.requestHeader(),
		Request: &pb.Operation{
			OperationUUID: proto.String(opUUID),
			Type:          pb.Operation_ASSET_UPLOAD_TOKEN_RETRIEVE_TYPE.Enum(),
			Last:          proto.Bool(true),
		},
		AssetUploadTokenRetrieveRequest: &pb.AssetUploadTokenRetrieveRequest{
			AssetUpload: []*pb.AssetUploadTokenRetrieveRequest_AssetUpload{{
				Record:     rid,
				RecordType: &pb.Record_Type{Name: proto.String(recordType)},
				Asset: &pb.AssetUploadTokenRetrieveRequest_AssetUpload_Asset{
					Name: &pb.AssetUploadTokenRetrieveRequest_AssetUpload_Name{Name: proto.String(field)},
					Data: data,
				},
			}},
			Header: []*pb.NamedHeader{
				{Name: proto.String("x-apple-mmcs-proto-version"), Value: proto.String("5.0")},
				{Name: proto.String("x-apple-mmcs-plist-sha256"), Value: proto.String(mmcsPlistSHA)},
				{Name: proto.String("x-apple-mmcs-plist-version"), Value: proto.String("v1.0")},
				{Name: proto.String("x-mme-client-info"), Value: proto.String(s.clientInfo())},
			},
			AuthorizePut: authPut,
			Unk1:         proto.Int32(1),
		},
	}
	encoded, err := proto.MarshalOptions{Deterministic: true}.Marshal(reqOp)
	if err != nil {
		return nil, err
	}
	framed := append(uleb128(uint64(len(encoded))), encoded...)

	req, _ := http.NewRequest("POST", ckGatewayBase+"/ckdatabase/api/client/asset/retrieve/token", bytes.NewReader(gzipBytes(framed)))
	s.applyCKHeaders(req)
	resp, err := httpClient(60 * time.Second).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode == 401 {
		return nil, ErrAuthExpired
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("asset/retrieve/token HTTP %d", resp.StatusCode)
	}
	body := maybeGunzip(raw)

	r := bytes.NewReader(body)
	res := &assetToken{}
	var resultErr string
	for {
		n, err := readUleb128(r)
		if err != nil {
			break
		}
		if n > uint64(r.Len()) {
			return nil, errors.New("truncated CloudKit response frame")
		}
		frame := make([]byte, n)
		io.ReadFull(r, frame)
		var op pb.ResponseOperation
		if err := proto.Unmarshal(frame, &op); err != nil {
			return nil, fmt.Errorf("decode CloudKit response: %w", err)
		}
		if rs := op.GetResult(); rs != nil && rs.Code != nil && rs.GetCode() != pb.RequestOperation_Result_SUCCESS {
			e := rs.GetError()
			resultErr = fmt.Sprintf("code=%d %s %s", rs.GetCode(), e.GetErrorKey(), e.GetErrorDescription())
		}
		up := op.GetAssetUploadTokenRetrieveResponse()
		if len(up.GetUploadInfo()) > 0 {
			res.uploadInfo = up.GetUploadInfo()
		}
		for _, ai := range up.GetAssetInfo() {
			if a := ai.GetAsset(); a != nil {
				res.host, res.container, res.dsid = a.GetHost(), a.GetContainer(), a.GetDsid()
			}
			if t := ai.GetEncodedInnerProto(); t != "" {
				res.token = t
			}
		}
	}
	if res.host == "" || res.token == "" {
		if resultErr == "" {
			resultErr = "no upload target in reply"
		}
		return nil, fmt.Errorf("asset upload token refused: %s", resultErr)
	}
	if !strings.HasPrefix(res.host, "http") {
		res.host = "https://" + res.host
	}
	return res, nil
}

func (s *ckSession) applyCKHeaders(req *http.Request) {
	for k, v := range s.ani.pick(mdKeys...) {
		req.Header.Set(k, v)
	}
	req.Header.Set("accept", "application/x-protobuf")
	req.Header.Set("accept-encoding", "gzip")
	req.Header.Set("accept-language", "en-US,en;q=0.9")
	req.Header.Set("cache-control", "no-transform")
	req.Header.Set("content-encoding", "gzip")
	req.Header.Set("content-type", `application/x-protobuf; desc="https://gateway.icloud.com:443/static/protobuf/CloudDB/CloudDBClient.desc"; messageType=RequestOperation; delimited=true`)
	req.Header.Set("user-agent", ckUserAgent)
	req.Header.Set("x-apple-operation-group-id", s.opGroup)
	req.Header.Set("x-apple-operation-id", s.op)
	req.Header.Set("x-apple-request-uuid", NewUUID())
	req.Header.Set("x-cloudkit-bundleid", s.bundle)
	req.Header.Set("x-cloudkit-containerid", s.container)
	req.Header.Set("x-cloudkit-databasescope", "Private")
	req.Header.Set("x-cloudkit-environment", "Production")
	req.Header.Set("x-mme-client-info", s.clientInfo())
	req.Header.Set("x-cloudkit-userid", s.userID)
	req.Header.Set("x-cloudkit-authtoken", s.cloudKitTok)
}

func (s *ckSession) applyMMCSHeaders(req *http.Request, dsid, auth string) {
	req.Header.Set("x-apple-mmcs-dataclass", "com.apple.Dataclass.CloudKit")
	req.Header.Set("x-apple-mmcs-auth", auth)
	req.Header.Set("Accept", "application/vnd.com.apple.me.ubchunk+protobuf")
	req.Header.Set("Content-Type", "application/vnd.com.apple.me.ubchunk+protobuf")
	req.Header.Set("x-apple-request-uuid", NewUUID())
	req.Header.Set("x-apple-mme-dsid", dsid)
	req.Header.Set("x-mme-client-info", s.clientInfo())
	req.Header.Set("Accept-Language", "en-us")
	req.Header.Set("User-Agent", ckUserAgent)
	req.Header.Set("x-apple-mmcs-proto-version", "5.0")
	req.Header.Set("x-apple-mmcs-plist-version", "v1.0")
	req.Header.Set("x-apple-mmcs-plist-sha256", mmcsPlistSHA)
	req.Header.Set("Accept-Encoding", "gzip, deflate")
	req.Header.Set("x-cloudkit-app-bundleid", s.bundle)
	req.Header.Set("x-cloudkit-container", s.container)
	req.Header.Set("x-cloudkit-databasescope", "Private")
	req.Header.Set("x-cloudkit-duetpreclearedmode", "None")
	req.Header.Set("x-cloudkit-environment", "production")
	req.Header.Set("x-cloudkit-zones", s.zone)
	req.Header.Set("x-cloudkit-deviceid", s.creds.HardwareID)
	req.Header.Set("x-apple-operation-group-id", s.opGroup)
	req.Header.Set("x-apple-operation-id", s.op)
}
