#!/usr/bin/env bash
# Select a development-only local key or a protected production signing key.

configure_signing() {
    if [ "$DEV_MODE" -eq 1 ]; then
        KEYSTORE="${DEV_KEYSTORE:-$PROJECT/tools/debug.keystore}"
        KEY_ALIAS="${DEV_KEY_ALIAS:-androiddebugkey}"
        STORE_PASS="${DEV_STORE_PASS:-android}"
        KEY_PASS="${DEV_KEY_PASS:-android}"

        if [ ! -f "$KEYSTORE" ]; then
            command -v keytool >/dev/null 2>&1 \
                || die "missing host tool: keytool (needed to create the dev keystore)"
            mkdir -p "$(dirname "$KEYSTORE")"
            keytool -genkeypair -noprompt \
                -keystore "$KEYSTORE" -storetype JKS \
                -storepass "$STORE_PASS" -alias "$KEY_ALIAS" -keypass "$KEY_PASS" \
                -keyalg RSA -keysize 2048 -validity 10000 \
                -dname "CN=Antigravity Dev,O=AGY,C=US" \
                || die "could not generate development keystore: $KEYSTORE"
            chmod 600 "$KEYSTORE"
            log "generated local development keystore: $KEYSTORE"
        fi
    else
        KEYSTORE="${AGY_RELEASE_KEYSTORE:-${KEYSTORE:-}}"
        KEY_ALIAS="${AGY_RELEASE_KEY_ALIAS:-${KEY_ALIAS:-}}"
        STORE_PASS="${AGY_RELEASE_STORE_PASS:-${STORE_PASS:-}}"
        KEY_PASS="${AGY_RELEASE_KEY_PASS:-${KEY_PASS:-}}"

        [ -n "$KEYSTORE" ] || die "release signing requires AGY_RELEASE_KEYSTORE"
        [ -n "$KEY_ALIAS" ] || die "release signing requires AGY_RELEASE_KEY_ALIAS"
        [ -n "$STORE_PASS" ] || die "release signing requires AGY_RELEASE_STORE_PASS"
        [ -n "$KEY_PASS" ] || die "release signing requires AGY_RELEASE_KEY_PASS"
    fi
    export KEYSTORE KEY_ALIAS STORE_PASS KEY_PASS
}
