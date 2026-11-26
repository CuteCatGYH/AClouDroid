package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func main() {
	var (
		listenAddr  string
		tokenFlag   string
		newToken    bool
		showURL     bool
		showVersion bool
		showHelp    bool
		tlsCert     string
		tlsKey      string
	)

	fs := flag.NewFlagSet("anisette-server", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	fs.StringVar(&listenAddr, "listen", "127.0.0.1:6969", "listen address (host:port)")
	fs.StringVar(&tokenFlag, "token", "", "override authentication token for this run")
	fs.BoolVar(&newToken, "new-token", false, "regenerate and save a new authentication token")
	fs.BoolVar(&showURL, "show-url", false, "print connection URLs and exit")
	fs.BoolVar(&showVersion, "version", false, "print version and exit")
	fs.BoolVar(&showHelp, "help", false, "show help message and exit")
	fs.StringVar(&tlsCert, "tls-cert", "", "PEM certificate chain file; enables HTTPS (needs --tls-key)")
	fs.StringVar(&tlsKey, "tls-key", "", "PEM private key file for --tls-cert")
	fs.Usage = printUsage

	if err := fs.Parse(os.Args[1:]); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			os.Exit(0)
		}
		os.Exit(2)
	}
	if fs.NArg() > 0 {
		fmt.Fprintf(os.Stderr, "unexpected argument: %s\n", fs.Arg(0))
		printUsage()
		os.Exit(2)
	}

	if showHelp {
		printUsage()
		return
	}
	if showVersion {
		fmt.Printf("AClouDroid Anisette Server %s (%s)\n", appVersion, platformName)
		return
	}

	host, port, err := net.SplitHostPort(listenAddr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "invalid --listen address %q: %v\n", listenAddr, err)
		os.Exit(2)
	}

	if (tlsCert == "") != (tlsKey == "") {
		fmt.Fprintln(os.Stderr, "--tls-cert and --tls-key must be given together")
		os.Exit(2)
	}
	var tlsConf *tls.Config
	var certNames []string
	if tlsCert != "" {
		pair, err := tls.LoadX509KeyPair(tlsCert, tlsKey)
		if err != nil {
			fmt.Fprintf(os.Stderr, "load TLS certificate: %v\n", err)
			os.Exit(1)
		}
		if leaf, err := x509.ParseCertificate(pair.Certificate[0]); err == nil {
			certNames = leaf.DNSNames
			if time.Now().After(leaf.NotAfter) {
				fmt.Fprintf(os.Stderr, "WARNING: TLS certificate expired on %s\n", leaf.NotAfter.Format("2006-01-02"))
			}
		}
		tlsConf = &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{pair}}
	}

	token, err := resolveToken(tokenFlag, newToken)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}

	if showURL {
		printURLs(port, token, !isLoopbackHost(host), certNames, tlsConf != nil)
		return
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "listen %s failed: %v\n", listenAddr, err)
		os.Exit(1)
	}
	actualAddr := ln.Addr().String()
	_, actualPort, _ := net.SplitHostPort(actualAddr)
	if actualPort == "" {
		actualPort = port
	}

	fmt.Printf("AClouDroid Anisette Server %s (%s)\n", appVersion, platformName)
	if tlsConf != nil {
		fmt.Printf("Listening on %s (HTTPS)\n", actualAddr)
	} else {
		fmt.Printf("Listening on %s\n", actualAddr)
	}
	if !isLoopbackHost(host) && tlsConf == nil {
		fmt.Fprintf(os.Stdout, "WARNING: plain HTTP on a non-loopback address (%s). Fine on home Wi-Fi or Tailscale; for a public IP use --tls-cert/--tls-key or an HTTPS tunnel.\n", actualAddr)
	}
	printURLs(actualPort, token, !isLoopbackHost(host), certNames, tlsConf != nil)

	if headers, err := ComputeAnisette(); err != nil {
		fmt.Printf("self-test FAILED: %v\n", err)
	} else {
		fmt.Printf("self-test OK (device %s)\n", firstClientInfoPart(headers["X-Mme-Client-Info"]))
	}

	srv := newHTTPServer(actualAddr, newAnisetteHandler(token, ComputeAnisette))
	if tlsConf != nil {
		srv.TLSConfig = tlsConf
		ln = tls.NewListener(ln, tlsConf)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}()

	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		fmt.Fprintf(os.Stderr, "server error: %v\n", err)
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Fprintf(os.Stderr, `AClouDroid Anisette Server %s (%s)

Usage:
  anisette-server [flags]

Flags:
  --listen host:port   Listen address (default "127.0.0.1:6969")
  --token value        Override authentication token for this run
  --new-token          Regenerate and persist a new authentication token
  --show-url           Print banner URLs and exit
  --version            Print version and exit
  --help               Show this help message and exit
  --tls-cert file      PEM certificate chain; serve HTTPS (use with --tls-key)
  --tls-key file       PEM private key for --tls-cert

Environment:
  ACLOUDDROID_ANISETTE_HOME   Override config directory (default: ~/Library/Application Support/AClouDroidAnisette)
`, appVersion, platformName)
}

// printURLs lists the URLs to paste into the App. LAN addresses are only reachable when the
// server listens beyond loopback, so they are hidden otherwise (a tunnel then forwards to
// 127.0.0.1 and the user pastes the tunnel's URL + /<token>/). With TLS, the phone checks the
// certificate's host name, so the URLs use the names in the certificate instead of raw IPs.
func printURLs(port, token string, lan bool, certNames []string, https bool) {
	fmt.Println("Paste one of these into AClouDroid -> 设置 -> 大文件上传 -> 设备指纹地址:")
	if https {
		for _, n := range certNames {
			fmt.Printf("  https://%s:%s/%s/\n", n, port, token)
		}
		if len(certNames) == 0 {
			fmt.Printf("  https://<name in your certificate>:%s/%s/\n", port, token)
		}
		return
	}
	if lan {
		for _, ip := range nonLoopbackIPv4s() {
			fmt.Printf("  http://%s:%s/%s/\n", ip, port, token)
		}
	} else {
		fmt.Println("  (loopback only: use --listen 0.0.0.0:" + port + " for LAN, or put a tunnel in front)")
	}
	fmt.Printf("  http://127.0.0.1:%s/%s/\n", port, token)
}

func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func nonLoopbackIPv4s() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	seen := make(map[string]struct{})
	var ips []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			v4 := ip.To4()
			if v4 == nil || v4.IsLoopback() || v4.IsLinkLocalUnicast() {
				continue
			}
			s := v4.String()
			if _, ok := seen[s]; !ok {
				seen[s] = struct{}{}
				ips = append(ips, s)
			}
		}
	}
	return ips
}

func firstClientInfoPart(clientInfo string) string {
	s := strings.TrimSpace(clientInfo)
	if s == "" {
		return "unknown"
	}
	if strings.HasPrefix(s, "<") {
		if end := strings.IndexByte(s, '>'); end > 0 {
			return s[:end+1]
		}
	}
	if fields := strings.Fields(s); len(fields) > 0 {
		return fields[0]
	}
	return s
}
