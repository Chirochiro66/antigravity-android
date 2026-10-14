# 00-env.sh -- core environment of the tlx prefix.
export HOME="${HOME:-$(dirname "$PREFIX")}"
export TMPDIR="$PREFIX/tmp"
export PATH="$PREFIX/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
if [ -f "$PREFIX/lib/liblse_compat.so" ]; then
    export LD_PRELOAD="$PREFIX/lib/liblse_compat.so:$PREFIX/lib/libtermux-exec.so"
else
    export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
fi
export TERMUX__PREFIX="$PREFIX"
export TERMUX__ROOTFS="/"
export TERMUX_APP__DATA_DIR="$(dirname "$PREFIX")"
# Bridges set the package id for core-launched tools; derive it for direct shells.
if [ -z "${AGY_APPLICATION_ID:-}" ]; then
    _agy_pkg_dir="$(dirname "$(dirname "$PREFIX")")"
    AGY_APPLICATION_ID="$(basename "$_agy_pkg_dir")"
    unset _agy_pkg_dir
fi
export AGY_APPLICATION_ID
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"
export LANG="${LANG:-C.UTF-8}"
export TZ="${TZ:-UTC}"
# CPU & toolchain compatibility: leave CFLAGS alone so per-toolchain tuning
# (e.g. a future -march=armv8.1-a on a device that has LSE in hardware) is not
# silently downgraded. The bootstrap ships liblse_compat.so for ARMv8.0-A, so
# explicit -march=armv8-a is unnecessary on older devices and harmful on newer
# ones. Callers that need the compatibility floor can set it themselves.
# export CFLAGS="${CFLAGS:-}"
# export CXXFLAGS="${CXXFLAGS:-}"
mkdir -p "$TMPDIR" "$HOME" 2>/dev/null || true
