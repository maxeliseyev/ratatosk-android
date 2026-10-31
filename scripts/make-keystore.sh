#!/usr/bin/env bash
# Create the release signing key and print how to put it in GitHub Secrets.
#
#   scripts/make-keystore.sh [path]
#
# The keystore and its password are what let a new APK update the installed
# app. BACK IT UP somewhere safe (password manager, offline copy): if it is
# lost, users cannot upgrade over the old install, only uninstall and install
# again. The file is gitignored.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/../ratatosk-android-secrets/ratatosk-release.jks}"
alias_name="${KEY_ALIAS:-ratatosk}"
subject="${KEY_SUBJECT:-CN=Ratatosk, OU=ratatosk-android, O=Ratatosk}"

if [ -f "$out" ]; then
    echo "keystore already exists: $out" >&2
    exit 1
fi

store_password="${STOREPASS:-$(openssl rand -base64 24)}"
key_password="${KEYPASS:-$store_password}"

mkdir -p "$(dirname "$out")"
keytool -genkeypair -v \
    -keystore "$out" \
    -alias "$alias_name" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storetype PKCS12 \
    -storepass "$store_password" -keypass "$key_password" \
    -dname "$subject"

cat <<EOF

keystore:  $out
alias:     $alias_name
password:  $store_password
           (same password for the key; both are the store password)

Back the file and the password up now — without them you cannot publish an
update that installs over an existing app.

Set the GitHub Secrets (repo: maxeliseyev/ratatosk-android):

  base64 < "$out" | tr -d '\\n' | gh secret set RATATOSK_KEYSTORE_BASE64
  gh secret set RATATOSK_KEYSTORE_PASSWORD --body '$store_password'
  gh secret set RATATOSK_KEY_PASSWORD        --body '$key_password'
  gh secret set RATATOSK_KEY_ALIAS           --body '$alias_name'

For local release builds, write keystore.properties (gitignored):

  RATATOSK_KEYSTORE_FILE=$out
  RATATOSK_KEYSTORE_PASSWORD=$store_password
  RATATOSK_KEY_PASSWORD=$key_password
  RATATOSK_KEY_ALIAS=$alias_name
EOF
