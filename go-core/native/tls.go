package native

// Apple's login host gsa.apple.com chains to "Apple Root CA", which macOS trusts but Android's
// system store does not ("x509: certificate signed by unknown authority", measured on the pad).
// Every native request therefore trusts the system roots PLUS Apple's two roots, embedded here.
// Only roots are added; nothing is skipped or disabled.
//
// apple_roots.pem (from macOS SystemRootCertificates.keychain), SHA-256:
//   Apple Root CA       B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24
//   Apple Root CA - G3  63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79

import (
	"crypto/tls"
	"crypto/x509"
	_ "embed"
	"net/http"
	"sync"
	"time"
)

//go:embed apple_roots.pem
var appleRootsPEM []byte

var (
	transportOnce sync.Once
	transport     *http.Transport
)

func appleTransport() *http.Transport {
	transportOnce.Do(func() {
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM(appleRootsPEM) {
			logf("warning: embedded Apple roots did not parse")
		}
		t := http.DefaultTransport.(*http.Transport).Clone()
		t.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}
		transport = t
	})
	return transport
}

// httpClient is the only way this package makes HTTP clients.
func httpClient(timeout time.Duration) *http.Client {
	return &http.Client{Timeout: timeout, Transport: appleTransport()}
}
