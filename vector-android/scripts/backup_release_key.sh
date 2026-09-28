#!/usr/bin/env bash
# Back up the release signing key, AND the password that opens it, as one file.
#
#   bash scripts/backup_release_key.sh verify   # does the key still open?
#   bash scripts/backup_release_key.sh backup   # write an encrypted bundle
#
# ## Why this exists
#
# The first Vector signing key is gone. `vector-release-DEAD-20260909.jks` still
# sits in the signing directory and cannot be opened by anything — the password
# that matched it was lost, so a 4096-bit key with 30 years of validity is now
# an inert 4 KB file. A new identity had to be minted on 2026-09-13.
#
# V6.1 follow-up §5 asked for the key to be backed up off the machine and named
# the risk precisely: "untracked is not the same as safe: a `git clean -xdf`
# destroys it". That risk is now closed separately — the key lives in
# ~/.local/share/vector-signing, outside the repository, so no git operation can
# reach it. This script closes the other half, which is media failure and the
# loss of the machine itself.
#
# ## The mistake this is shaped to prevent
#
# A keystore backed up WITHOUT its password is not a backup — that is exactly
# how the first key was lost. So the bundle contains both, and the encryption
# passphrase is the only thing you must remember. Put that passphrase in a
# password manager; do not put it next to the bundle.
#
# Nothing here is committable and nothing leaves the machine on its own. The
# bundle is written to the signing directory; copying it somewhere else is a
# deliberate act and step 4 tells you to do it.
set -euo pipefail

SIGDIR="${VECTOR_SIGNING_DIR:-$HOME/.local/share/vector-signing}"
GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"

prop() { grep -E "^$1=" "$GRADLE_PROPS" 2>/dev/null | head -1 | cut -d= -f2-; }

KEYSTORE="$(prop vectorKeystore)"
ALIAS="$(prop vectorKeyAlias)"
PASSWORD="$(prop vectorKeystorePassword)"

[[ -n "$KEYSTORE" ]] || { echo "no vectorKeystore in $GRADLE_PROPS"; exit 1; }
[[ -f "$KEYSTORE" ]] || { echo "keystore not found: $KEYSTORE"; exit 1; }
command -v keytool >/dev/null || { echo "keytool not found — install a JDK"; exit 1; }

# Does the key actually open? This is the check nobody ran on the first key
# until it was needed, by which point it was too late to do anything about it.
check_opens() {
  keytool -list -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" >/dev/null 2>&1
}

fingerprint() {
  keytool -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" 2>/dev/null \
    | grep -m1 "SHA256:" | sed 's/^[[:space:]]*//'
}

case "${1:-verify}" in
  verify)
    echo "keystore : $KEYSTORE"
    echo "alias    : $ALIAS"
    if check_opens; then
      echo "opens    : YES"
      echo "$(fingerprint)"
      echo
      echo "This is the identity every Vector release is signed with. If it ever"
      echo "stops matching, an installed Vector cannot be upgraded in place."
    else
      echo "opens    : NO  <-- the password in $GRADLE_PROPS does not match this keystore."
      echo
      echo "Do not overwrite either one. If a working backup bundle exists, restore"
      echo "from it now; that is the only thing that can still recover this key."
      exit 1
    fi
    ;;

  backup)
    check_opens || { echo "refusing to back up a keystore that does not open. run 'verify' first."; exit 1; }
    command -v gpg >/dev/null || { echo "gpg not found"; exit 1; }

    STAMP="$(date +%Y%m%d)"
    STAGE="$(mktemp -d)"
    trap 'rm -rf "$STAGE"' EXIT
    OUT="$SIGDIR/vector-signing-backup-$STAMP.tar.gz.gpg"

    cp "$KEYSTORE" "$STAGE/$(basename "$KEYSTORE")"
    cat > "$STAGE/RESTORE.txt" <<EOF
Vector release signing — backup taken $(date -Iseconds)

keystore file : $(basename "$KEYSTORE")
alias         : $ALIAS
store/key pass: $PASSWORD
$(fingerprint)

To restore:

  mkdir -p ~/.local/share/vector-signing && chmod 700 ~/.local/share/vector-signing
  cp $(basename "$KEYSTORE") ~/.local/share/vector-signing/
  chmod 600 ~/.local/share/vector-signing/$(basename "$KEYSTORE")

then put these three lines in ~/.gradle/gradle.properties:

  vectorKeystore=\$HOME/.local/share/vector-signing/$(basename "$KEYSTORE")
  vectorKeyAlias=$ALIAS
  vectorKeystorePassword=$PASSWORD

and confirm with:

  bash vector-android/scripts/backup_release_key.sh verify

The fingerprint above must match, or it is a different identity and Android
will refuse to upgrade over an installed Vector.
EOF

    tar -czf "$STAGE/bundle.tar.gz" -C "$STAGE" "$(basename "$KEYSTORE")" RESTORE.txt

    echo "Choose a passphrase for the backup. Put it in a password manager."
    echo "It is the only thing standing between this file and your signing key,"
    echo "and the only thing that can open it later. Nothing else knows it."
    echo
    # Read it here rather than letting gpg prompt twice: the same passphrase has
    # to encrypt the bundle and then decrypt it for the verification below, and
    # being asked for it twice invites a typo in the second one that looks like
    # a corrupt backup.
    read -rsp "passphrase: " PP1; echo
    read -rsp "again     : " PP2; echo
    [[ -n "$PP1" ]] || { echo "empty passphrase — refusing."; exit 1; }
    [[ "$PP1" == "$PP2" ]] || { echo "they do not match — nothing written."; exit 1; }

    gpg --batch --yes --pinentry-mode loopback --passphrase-fd 3 \
        --symmetric --cipher-algo AES256 \
        --output "$OUT" "$STAGE/bundle.tar.gz" 3<<<"$PP1"
    chmod 600 "$OUT"

    # A backup nobody has opened is a belief, not a backup. Decrypt it back and
    # list what is inside, so the thing you carry away has been proven to open.
    echo
    echo "verifying the bundle decrypts and contains the key..."
    gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 \
        --decrypt "$OUT" 3<<<"$PP1" 2>/dev/null | tar -tzf - | sed 's/^/  /'
    unset PP1 PP2

    echo
    echo "wrote $OUT  ($(stat -c%s "$OUT") bytes)"
    echo
    echo "NOW COPY IT OFF THIS MACHINE. Until you do, this is still one disk:"
    echo "  - a USB stick or external drive, or"
    echo "  - any cloud storage (it is encrypted; that is the point), or"
    echo "  - a second computer."
    echo
    echo "Then store the passphrase somewhere that is NOT next to the bundle."
    ;;

  *)
    echo "usage: bash scripts/backup_release_key.sh {verify|backup}"
    exit 2
    ;;
esac
