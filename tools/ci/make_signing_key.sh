#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Generate the release signing keystore for the GitHub Actions workflow and print
# the four repository secrets it expects. Run this LOCALLY; the keystore and its
# passwords are private key material and must never be committed or pasted into
# an issue, a PR or a chat window.
set -euo pipefail

KEYSTORE="${KEYSTORE:-sillytavern-android-release.jks}"
ALIAS="${ALIAS:-sillytavern-android}"
DNAME="${DNAME:-CN=SillyTavern Android (unofficial shell), OU=None, O=None, L=None, ST=None, C=ZZ}"
VALIDITY="${VALIDITY:-10000}"

if [ -e "$KEYSTORE" ]; then
  echo "Refusing to overwrite existing $KEYSTORE: reuse it, or set KEYSTORE=<path>." >&2
  echo "Signing an update with a different key forces users to uninstall first." >&2
  exit 1
fi

read -r -s -p "Keystore password (>= 6 chars): " STORE_PASSWORD; echo
read -r -s -p "Repeat keystore password: " STORE_PASSWORD_CONFIRM; echo
if [ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]; then
  echo "Passwords do not match." >&2
  exit 1
fi
if [ "${#STORE_PASSWORD}" -lt 6 ]; then
  echo "keytool requires at least 6 characters." >&2
  exit 1
fi

# One password for store and key keeps the secret set small; the workflow still
# passes them separately, so you can split them later without touching the YAML.
keytool -genkeypair -v \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity "$VALIDITY" \
  -dname "$DNAME" \
  -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD"

BASE64_FILE="${KEYSTORE}.base64"
base64 -w0 < "$KEYSTORE" > "$BASE64_FILE" 2>/dev/null || base64 < "$KEYSTORE" | tr -d '\n' > "$BASE64_FILE"

cat <<INFO

Keystore written to: $KEYSTORE
Base64 (one line)  : $BASE64_FILE

Set the four repository secrets (Settings > Secrets and variables > Actions),
or with the GitHub CLI from this directory:

  gh secret set ANDROID_KEYSTORE_BASE64   < "$BASE64_FILE"
  gh secret set ANDROID_KEYSTORE_PASSWORD # paste the keystore password
  gh secret set ANDROID_KEY_ALIAS         # paste: $ALIAS
  gh secret set ANDROID_KEY_PASSWORD      # paste the key password (same as above unless you split them)

Then back up $KEYSTORE and the passwords offline and delete $BASE64_FILE:
losing this key means no signed update can ever replace an installed build.
INFO
