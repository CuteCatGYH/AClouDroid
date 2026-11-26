//go:build darwin

package main

/*
#cgo LDFLAGS: -framework Foundation
#include <stdlib.h>
#include "anisette_darwin.h"
*/
import "C"

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
	"unsafe"
)

var anisetteMu sync.Mutex

func cStr(p *C.char) string {
	if p == nil {
		return ""
	}
	return C.GoString(p)
}

// ComputeAnisette invokes macOS private frameworks (AOSKit + AuthKit) under a mutex
// and returns a fresh set of Apple anisette headers.
func ComputeAnisette() (map[string]string, error) {
	anisetteMu.Lock()
	defer anisetteMu.Unlock()

	var res C.AnisetteResult
	var cErr *C.char
	rc := C.fetch_anisette(&res, &cErr)
	defer C.free_anisette_result(&res)
	if cErr != nil {
		defer C.free(unsafe.Pointer(cErr))
	}
	if rc != 0 {
		msg := cStr(cErr)
		if msg == "" {
			msg = "Apple framework call failed"
		}
		return nil, errors.New(msg)
	}

	md := strings.TrimSpace(cStr(res.md))
	mdm := strings.TrimSpace(cStr(res.mdm))
	deviceID := strings.ToUpper(strings.TrimSpace(cStr(res.device_id)))
	if md == "" || mdm == "" || deviceID == "" {
		missing := make([]string, 0, 3)
		if md == "" {
			missing = append(missing, "X-Apple-I-MD")
		}
		if mdm == "" {
			missing = append(missing, "X-Apple-I-MD-M")
		}
		if deviceID == "" {
			missing = append(missing, "X-Mme-Device-Id")
		}
		return nil, fmt.Errorf("missing required anisette field(s): %s", strings.Join(missing, ", "))
	}

	lu := strings.TrimSpace(cStr(res.lu))
	clientInfo := strings.TrimSpace(cStr(res.client_info))
	locale := strings.TrimSpace(cStr(res.locale))
	if locale == "" {
		locale = "en_US"
	}
	serial := strings.TrimSpace(cStr(res.serial))

	now := time.Now()
	tz, _ := now.Zone()
	if tz == "" {
		tz = "UTC"
	}

	headers := map[string]string{
		"X-Apple-I-MD":          md,
		"X-Apple-I-MD-M":        mdm,
		"X-Apple-I-MD-LU":       lu,
		"X-Apple-I-MD-RINFO":    "17106176",
		"X-Mme-Device-Id":       deviceID,
		"X-Mme-Client-Info":     clientInfo,
		"X-Apple-I-Client-Time": now.UTC().Format("2006-01-02T15:04:05Z"),
		"X-Apple-I-TimeZone":    tz,
		"X-Apple-Locale":        locale,
	}
	if serial != "" {
		headers["X-Apple-I-SRL-NO"] = serial
	}

	return headers, nil
}
