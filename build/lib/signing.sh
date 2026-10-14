#!/usr/bin/env bash
#
# signing.sh - Signing identity resolution for release and dev builds.
#

configure_signing() {
    if [ "$DEV_MODE" -eq 1 ]; then
        KEYSTORE="${AGY_DEV_KEYSTORE:-$PROJECT/tools/debug.keystore}"
        KEY_ALIAS="${AGY_DEV_KEY_ALIAS:-androiddebugkey}"
        KEY_PASS="${AGY_DEV_KEY_PASS:-android}"
        STORE_PASS="${AGY_DEV_STORE_PASS:-android}"

        if [ ! -f "$KEYSTORE" ]; then
            log "Generating development keystore: $KEYSTORE"
            mkdir -p "$(dirname "$KEYSTORE")"
            keytool -genkey -v -keystore "$KEYSTORE" \
                -storepass "$STORE_PASS" -alias "$KEY_ALIAS" -keypass "$KEY_PASS" \
                -keyalg RSA -keysize 2048 -validity 10000 \
                -dname "CN=Android Debug,O=Android,C=US" || die "failed to generate dev keystore"
        fi
        export KEYSTORE KEY_ALIAS KEY_PASS STORE_PASS
    else
        KEYSTORE="${AGY_RELEASE_KEYSTORE:-${KEYSTORE:-}}"
        KEY_ALIAS="${AGY_RELEASE_KEY_ALIAS:-${KEY_ALIAS:-}}"
        KEY_PASS="${AGY_RELEASE_KEY_PASS:-${KEY_PASS:-}}"
        STORE_PASS="${AGY_RELEASE_STORE_PASS:-${STORE_PASS:-}}"

        if [ -z "$KEYSTORE" ] || [ -z "$KEY_ALIAS" ] || [ -z "$STORE_PASS" ] || [ -z "$KEY_PASS" ]; then
            die "Release builds require AGY_RELEASE_KEYSTORE, AGY_RELEASE_KEY_ALIAS, AGY_RELEASE_STORE_PASS, and AGY_RELEASE_KEY_PASS."
        fi
        [ -f "$KEYSTORE" ] || die "Release keystore not found: $KEYSTORE"
        export KEYSTORE KEY_ALIAS KEY_PASS STORE_PASS
    fi
}
