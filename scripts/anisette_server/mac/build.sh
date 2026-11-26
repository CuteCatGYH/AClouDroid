#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OUT="$SCRIPT_DIR/anisette-server"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/anisette-build.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

export MACOSX_DEPLOYMENT_TARGET=12.0
export CGO_ENABLED=1
export GOOS=darwin

echo "Building arm64..."
GOARCH=arm64 \
  CGO_CFLAGS="-arch arm64 -mmacosx-version-min=12.0" \
  CGO_LDFLAGS="-arch arm64 -mmacosx-version-min=12.0" \
  go build -trimpath -ldflags="-s -w" -o "$TMP_DIR/anisette-server-arm64" .

echo "Building amd64..."
GOARCH=amd64 \
  CGO_CFLAGS="-arch x86_64 -mmacosx-version-min=12.0" \
  CGO_LDFLAGS="-arch x86_64 -mmacosx-version-min=12.0" \
  go build -trimpath -ldflags="-s -w" -o "$TMP_DIR/anisette-server-amd64" .

echo "Creating universal binary with lipo..."
lipo -create \
  -output "$OUT" \
  "$TMP_DIR/anisette-server-arm64" \
  "$TMP_DIR/anisette-server-amd64"
chmod +x "$OUT"

echo "Built: $OUT"
lipo -info "$OUT"
