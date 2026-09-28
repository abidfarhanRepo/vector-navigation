# Where the JDK and the Android SDK are, resolved rather than asserted.
#
# Four scripts each carried their own copy of this and three of the copies had
# gone stale: `$HOME/Development/jdk` and `$HOME/Development/android-sdk` exist
# on one machine and on this one they do not, so `simulate_drive.sh`,
# `uidriver.sh` and `inject_failures.sh` all failed at the first `adb` with
# "JAVA_HOME is set to an invalid directory" — a confusing five minutes for
# anyone who has just been told by the README that both are already exported
# from `~/.bashrc`.
#
# `verify_on_device.sh` had already grown a search loop for exactly this reason
# and its comment says why: "these were single hardcoded paths that exist on one
# machine". This is that loop, moved somewhere the other three can reach it.
#
#   source "$(dirname "${BASH_SOURCE[0]}")/toolchain.sh"
#
# An exported value always wins. The fallbacks are the layouts this project has
# actually been run against, newest JDK first — AGP 8.x wants 17 or above and
# the README specifies 21.
for _d in "$HOME/Development/android-sdk" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
  [ -d "${ANDROID_HOME:-}" ] && break
  [ -d "$_d" ] && ANDROID_HOME="$_d"
done
for _d in "$HOME/Development/jdk" /usr/lib/jvm/java-21-openjdk \
          /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/default; do
  [ -d "${JAVA_HOME:-}" ] && break
  [ -d "$_d" ] && JAVA_HOME="$_d"
done
unset _d
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=/usr/lib/jvm/default}"
export ANDROID_HOME JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
