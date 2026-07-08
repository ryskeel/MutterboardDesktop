#!/usr/bin/env bash
# Builds the Flatpak bundle for a release:
#   1. gradle createDistributable  (self-contained app image with jlinked JVM)
#   2. flatpak-builder             (assembles /app per the manifest)
#   3. flatpak build-bundle        (single .flatpak file for GitHub releases)
#
# Output: build/Mutterboard-<version>-x86_64.flatpak
set -euo pipefail
cd "$(dirname "$0")/.."

APP_ID="io.github.ryskeel.Mutterboard"
VERSION=$(grep -oP '^version = "\K[^"]+' build.gradle.kts)

# Build with a standard portable JDK, not Homebrew's: jpackage stamps its
# launcher with the build JDK's ELF interpreter, and brew's is
# /home/linuxbrew/... which exists on no one else's machine.
TEMURIN=$(ls -d "$HOME"/.jdks/jdk-21* 2>/dev/null | sort -V | tail -1)
if [ -z "$TEMURIN" ]; then
  echo "Temurin 21 not found; downloading to ~/.jdks..."
  mkdir -p "$HOME/.jdks"
  curl -sL -o "$HOME/.jdks/temurin21.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$HOME/.jdks/temurin21.tar.gz" -C "$HOME/.jdks"
  rm "$HOME/.jdks/temurin21.tar.gz"
  TEMURIN=$(ls -d "$HOME"/.jdks/jdk-21* | sort -V | tail -1)
fi

./gradlew -Dorg.gradle.java.home="$TEMURIN" createDistributable

# Refuse to ship a launcher whose interpreter isn't the standard glibc one.
INTERP=$(file build/compose/binaries/main/app/Mutterboard/bin/Mutterboard \
  | grep -oP 'interpreter \K[^,]+')
case "$INTERP" in
  /lib64/ld-linux-x86-64.so.2|/lib/ld-linux-x86-64.so.2) ;;
  *) echo "ERROR: launcher interpreter is '$INTERP' — not portable. Wrong JDK?"; exit 1 ;;
esac

flatpak run org.flatpak.Builder \
  --force-clean --user \
  --repo=build/flatpak-repo \
  build/flatpak-build \
  "flatpak/$APP_ID.yml"

BUNDLE="build/Mutterboard-$VERSION-x86_64.flatpak"
flatpak build-bundle build/flatpak-repo "$BUNDLE" "$APP_ID"
echo "Bundle: $BUNDLE"
