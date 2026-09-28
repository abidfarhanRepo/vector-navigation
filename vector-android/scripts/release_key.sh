#!/usr/bin/env bash
# Create the release signing key, if there isn't one.
#
# Vector is self-hosted: users build it themselves and point it at their own
# backend, so there is no Play listing whose upload signature has to stay
# stable and no reason for a shared key to exist. "Your build, your key" is
# both the honest model and the safe one.
#
# The key goes in ~/.local/share/vector-signing, never in the repository or
# beside it, and the passwords go in ~/.gradle/gradle.properties. Nothing this
# script writes is committable, and `.gitignore` refuses *.jks as a second line
# of defence.
#
# It used to be written next to the repository. V6.1 follow-up §5 pointed out
# that "untracked is not the same as safe: a `git clean -xdf` destroys it", so
# it now lives somewhere no git operation can reach. Back it up as well —
# `scripts/backup_release_key.sh` does that, and explains why a keystore saved
# without its password is not a backup.
#
#   bash scripts/release_key.sh
#   ./gradlew assembleRelease -PvectorBase=... -PvectorToken=...
#
# Without a key, `assembleRelease` still succeeds and still produces
# `*-release-unsigned.apk`. That is deliberate — a machine without the key can
# compile and test a release variant, it just cannot ship one.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIGDIR="${VECTOR_SIGNING_DIR:-$HOME/.local/share/vector-signing}"
KEYSTORE="${VECTOR_KEYSTORE:-$SIGDIR/vector-release.jks}"
ALIAS="${VECTOR_KEY_ALIAS:-vector}"
GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"

if [[ -f "$KEYSTORE" ]]; then
  echo "keystore already exists: $KEYSTORE"
  echo "delete it by hand if you really mean to replace it — a new key is a"
  echo "new app identity, and Android will refuse to upgrade over the old one."
  exit 0
fi

# A generated passphrase rather than a prompt. This key protects a self-built
# binary on the developer's own machine; a memorable password buys nothing and
# a prompt makes the script unusable from a batch.
PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"

command -v keytool >/dev/null || { echo "keytool not found — install a JDK"; exit 1; }

mkdir -p "$SIGDIR"
chmod 700 "$SIGDIR"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Vector, OU=Self-hosted, O=Vector, L=Doha, C=QA"

chmod 600 "$KEYSTORE"

mkdir -p "$(dirname "$GRADLE_PROPS")"
touch "$GRADLE_PROPS"
# Replace any previous block rather than appending a second one, or the last
# value silently wins and nobody can tell which key is being used.
python3 - "$GRADLE_PROPS" "$KEYSTORE" "$ALIAS" "$PASS" <<'PY'
import re, sys
path, keystore, alias, password = sys.argv[1:5]
text = open(path).read()
text = re.sub(r"\n?# --- vector release signing ---.*?# --- end vector ---\n?", "\n", text, flags=re.S)
text = text.rstrip("\n")
if text:
    text += "\n"
text += (
    "# --- vector release signing ---\n"
    "# Written by vector-android/scripts/release_key.sh. Outside the repo on purpose.\n"
    f"vectorKeystore={keystore}\n"
    f"vectorKeyAlias={alias}\n"
    f"vectorKeystorePassword={password}\n"
    "# --- end vector ---\n"
)
open(path, "w").write(text)
PY
chmod 600 "$GRADLE_PROPS"

echo
echo "keystore:  $KEYSTORE"
echo "passwords: $GRADLE_PROPS  (chmod 600, outside the repository)"
echo
echo "release builds will now be signed. Verify with:"
echo "  bash scripts/backup_release_key.sh verify"
echo
echo "then back it up off this machine — a key that exists on one disk is a key"
echo "you are going to lose:"
echo "  bash scripts/backup_release_key.sh backup"
