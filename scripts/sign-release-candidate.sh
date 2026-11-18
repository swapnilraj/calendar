#!/usr/bin/env bash
# The signing identity is supplied by the owner; this script never creates a key.
set -euo pipefail
umask 077
if [[ $# != 2 ]]; then
  echo "Usage: sign-release-candidate.sh unsigned.apk output.apk" >&2
  exit 2
fi
for signing_name in ANDROID_HOME DAYLIGHT_KEYSTORE_B64 DAYLIGHT_KEYSTORE_PASSWORD DAYLIGHT_KEY_ALIAS DAYLIGHT_KEY_PASSWORD DAYLIGHT_SIGNING_CERT_SHA256; do
  if [[ -z ${!signing_name:-} ]]; then
    echo "Missing signing configuration: $signing_name" >&2
    exit 1
  fi
done
unsigned_apk=$1
signed_apk=$2
[[ -f "$unsigned_apk" && ! -e "$signed_apk" ]] || { echo "Input missing or output already exists" >&2; exit 1; }
signing_tools="$ANDROID_HOME/build-tools/37.0.0"
[[ -x "$signing_tools/zipalign" && -x "$signing_tools/apksigner" ]] || { echo "Build tools 37.0.0 required" >&2; exit 1; }
signing_temp=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/daylight-sign.XXXXXXXX")
trap 'rm -rf -- "$signing_temp"' EXIT
python3 - "$signing_temp/owner.keystore" <<'PY'
import base64, os, pathlib, sys
pathlib.Path(sys.argv[1]).write_bytes(base64.b64decode(os.environ['DAYLIGHT_KEYSTORE_B64'], validate=True))
PY
"$signing_tools/zipalign" -P 16 -f 4 "$unsigned_apk" "$signing_temp/aligned.apk"
"$signing_tools/apksigner" sign --ks "$signing_temp/owner.keystore" \
  --ks-key-alias "$DAYLIGHT_KEY_ALIAS" --ks-pass env:DAYLIGHT_KEYSTORE_PASSWORD \
  --key-pass env:DAYLIGHT_KEY_PASSWORD --out "$signing_temp/signed.apk" "$signing_temp/aligned.apk"
"$signing_tools/apksigner" verify --verbose --print-certs "$signing_temp/signed.apk" > "$signing_temp/verify.txt"
python3 - "$signing_temp/verify.txt" <<'PY'
import os, pathlib, re, sys
expected = re.sub(r'[:\s]', '', os.environ['DAYLIGHT_SIGNING_CERT_SHA256']).lower()
report = pathlib.Path(sys.argv[1]).read_text()
actual = re.findall(r'^(?:Signer #\d+|V[0-9]+(?:\.[0-9]+)* Signer): certificate SHA-256 digest: ([0-9a-fA-F]{64})$', report, re.M)
one_signer = re.search(r'^Number of signers: 1$', report, re.M) is not None
if not re.fullmatch(r'[0-9a-f]{64}', expected) or not one_signer or not actual or {value.lower() for value in actual} != {expected}:
    raise SystemExit('Signing certificate does not match the configured stable identity')
PY
# Publish output only after its signature and pinned public certificate are verified.
mkdir -p -- "$(dirname -- "$signed_apk")"
cp -- "$signing_temp/signed.apk" "$signed_apk"
echo "Signed candidate verified against the configured certificate"
