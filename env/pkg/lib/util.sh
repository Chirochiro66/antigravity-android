#!/data/data/com.agy/files/usr/bin/bash
# util.sh -- shared helpers for pkg.

PKG_VERSION="0.1.0"

: "${PREFIX:?PREFIX must be exported by the AGY shell profile or pkg wrapper}"
PKG_ROOT="${PKG_ROOT:-$PREFIX/var/lib/pkg}"
PKG_DB="$PKG_ROOT/installed"
PKG_CACHE="$PKG_ROOT/cache"
PKG_INDEX="$PKG_ROOT/index"
PKG_ETC="$PREFIX/etc/pkg"

# TLS trust: the overlay ships the bundle at $PREFIX/etc/tls/cert.pem. Termux's
# curl is compiled with a /data/data/com.termux/... default, so point it at ours.
PKG_CERT="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_FILE="$PKG_CERT"
export CURL_CA_BUNDLE="$PKG_CERT"
export OPENSSL_CONF="${OPENSSL_CONF:-$PREFIX/etc/tls/openssl.cnf}"

log()  { printf 'pkg: %s\n' "$*" >&2; }
warn() { printf 'pkg: warn: %s\n' "$*" >&2; }
die()  { printf 'pkg: error: %s\n' "$*" >&2; exit 1; }

ensure_dirs() { mkdir -p "$PKG_DB" "$PKG_CACHE" "$PKG_INDEX" "$PKG_ROOT/tmp"; }

# rewrite_prefix DIRS... -- rewrite legacy /data/data and modern
# /data/user/<id> app-private paths to the active $PREFIX. Every occurrence is
# replaced, not only the shebang, so helper bodies and generated .list files
# work after extraction. Text-only, no symlink traversal, idempotent.
rewrite_prefix() {
    local f esc pattern='/data/(data|user/[0-9]+)/[^/]*/files/usr'
    # Escape sed-replacement metacharacters in $PREFIX (never expected, cheap).
    esc="$(printf '%s' "$PREFIX" | sed 's/[\\&|]/\\&/g')"
    # One grep walk finds every matching text file, then one sed per file.
    grep -rIlZ -E -e "$pattern" -- "$@" 2>/dev/null \
        | while IFS= read -r -d '' f; do
            sed -i -E "s|$pattern|$esc|g" "$f"
        done
}

load_conf() {
    [ -f "$PKG_ETC/repo.conf" ] && . "$PKG_ETC/repo.conf"
    : "${PKG_REPOS:=https://packages.termux.dev/apt/termux-main}"
    : "${PKG_CHANNEL:=stable}"
    : "${PKG_COMPONENT:=main}"
    : "${PKG_ARCH:=aarch64}"
    # data.tar paths are ./data/data/com.<id>/files/usr/... -> 6 components.
    # deb_files strips by component count, not by a hardcoded prefix string.
    : "${PKG_DEB_STRIP:=6}"
}
