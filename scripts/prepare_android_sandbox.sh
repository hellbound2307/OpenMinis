#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

# Termux proot package — aarch64 static binary
# NOTE: upstream renamed the version scheme from 5.1.107-70 to 5.1.107.NN;
# 5.1.107-70 no longer exists in the pool (404). Pinned to current 5.1.107.92.
PROOT_VERSION="5.1.107.92"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

# --- Alpine rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Alpine rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    curl -fSL -o "$ROOTFS_FILE" "$ALPINE_URL"
    echo "✓ Downloaded: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- ripgrep (rg) + runtime libs for the sandbox overlay [T-android-ripgrep] ---
# Vendored into assets/default_mount so the agent's sandbox has rg from first
# boot and it SURVIVES rootfs resets (overlay is re-applied on every boot).
# Alpine ships rg in the community repo; it needs libpcre2-8 + libgcc_s from
# main. All three are plain gzip-tar apks for aarch64 — extracted directly.
RG_VERSION="14.1.1-r0"
PCRE2_VERSION="10.43-r0"
LIBGCC_VERSION="14.2.0-r4"
ALPINE_MAIN_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/main/aarch64"
ALPINE_COMMUNITY_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/community/aarch64"
RG_OVERLAY="$ASSETS_DIR/default_mount"

fetch_apk_extract () {
    # $1 = full apk url, $2 = dest cache file, $3 = dest extract dir
    if [ ! -f "$2" ]; then
        echo "Downloading $(basename "$2")..."
        curl -fSL -o "$2" "$1"
    fi
    mkdir -p "$3"
    tar xzf "$2" -C "$3"
}

RG_TMP="$(mktemp -d)"
trap 'rm -rf "$RG_TMP"' EXIT

fetch_apk_extract "$ALPINE_COMMUNITY_URL/ripgrep-$RG_VERSION.apk" "$RG_TMP/rg.apk" "$RG_TMP/rg"
fetch_apk_extract "$ALPINE_MAIN_URL/pcre2-$PCRE2_VERSION.apk" "$RG_TMP/pcre2.apk" "$RG_TMP/pcre2"
fetch_apk_extract "$ALPINE_MAIN_URL/libgcc-$LIBGCC_VERSION.apk" "$RG_TMP/libgcc.apk" "$RG_TMP/libgcc"

mkdir -p "$RG_OVERLAY/usr/local/bin" "$RG_OVERLAY/usr/lib"
install -m 0755 "$RG_TMP/rg/usr/bin/rg" "$RG_OVERLAY/usr/local/bin/rg"
cp -a "$RG_TMP/pcre2/usr/lib/libpcre2-8.so.0" "$RG_TMP/pcre2/usr/lib/libpcre2-8.so.0.12.0" "$RG_OVERLAY/usr/lib/"
cp -a "$RG_TMP/libgcc/usr/lib/libgcc_s.so.1" "$RG_OVERLAY/usr/lib/"
echo "✓ ripgrep $RG_VERSION + pcre2/libgcc vendored into default_mount"

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    echo "Downloading PRoot ${PROOT_VERSION} aarch64 from Termux..."

    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    DEB_FILE="$TMPDIR/proot.deb"
    curl -fSL -o "$DEB_FILE" "$PROOT_DEB_URL"

    # Extract .deb (it's an ar archive containing data.tar.xz)
    cd "$TMPDIR"
    ar x "$DEB_FILE"

    # Extract data archive
    if [ -f "data.tar.xz" ]; then
        tar xf data.tar.xz
    elif [ -f "data.tar.gz" ]; then
        tar xzf data.tar.gz
    elif [ -f "data.tar.zst" ]; then
        zstd -d data.tar.zst -o data.tar
        tar xf data.tar
    else
        echo "Error: Could not find data archive in .deb"
        ls -la "$TMPDIR"
        exit 1
    fi

    # Find the proot binary
    PROOT_BIN=$(find "$TMPDIR" -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "Error: Could not find proot binary in extracted .deb"
        find "$TMPDIR" -type f
        exit 1
    fi

    cp "$PROOT_BIN" "$PROOT_FILE"
    chmod +x "$PROOT_FILE"
    cd "$PROJECT_ROOT"

    echo "✓ Extracted PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"
fi

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
