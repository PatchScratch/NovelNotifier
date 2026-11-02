#!/usr/bin/env bash
# Build a real, single-file AppImage of the NovelNotifier companion.
#
# The Compose plugin's packageAppImage task emits jpackage's *directory*
# "app-image"; this script wraps that into a portable AppImage with
# appimagetool (AppDir + embedded desktop entry + icon).
#
# Usage:  companion/dev/build-appimage.sh
# Out:    companion/dist/NovelNotifierCompanion-<version>-x86_64.AppImage
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep -oP 'packageVersion\s*=\s*"\K[^"]+' build.gradle.kts)"
APP_NAME="NovelNotifierCompanion"
OUT="dist/${APP_NAME}-${VERSION}-x86_64.AppImage"
IMAGE_DIR="build/compose/binaries/main/app/${APP_NAME}"

# jpackage needs a FULL JDK (jlink+jpackage): system java-21 is headless
# and the Studio JBR has jlink but no jpackage. Prefer the foojay-provisioned
# Temurin when it exists; otherwise let Gradle use the current JVM.
TEMURIN="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"
JVM_ARGS=()
[ -d "$TEMURIN" ] && JVM_ARGS+=("-Dorg.gradle.java.home=$TEMURIN")

echo ">> packageAppImage (jlink runtime)…"
./gradlew packageAppImage "${JVM_ARGS[@]+${JVM_ARGS[@]}}"

APPDIR="build/appimage/AppDir"
rm -rf "$APPDIR" dist
mkdir -p "$APPDIR" dist

# The jpackage app image is relocatable (launcher resolves its own path).
cp -r "$IMAGE_DIR" "$APPDIR/$APP_NAME"

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/NovelNotifierCompanion/bin/NovelNotifierCompanion" "$@"
EOF
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/${APP_NAME}.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=NovelNotifier Companion
GenericName=Library management companion
Comment=Manage the NovelNotifier library on your phone from the desktop
Exec=NovelNotifierCompanion
Icon=novelnotifier-companion
Terminal=false
Categories=Office;Utility;
Keywords=novel;library;novelnotifier;bookshelf;
StartupWMClass=NovelNotifierCompanion
EOF

cp icon-512.png "$APPDIR/novelnotifier-companion.png"

echo ">> appimagetool…"
mkdir -p "$HOME/.cache/novelnotifier-companion"
TOOL="$(command -v appimagetool || true)"
if [ -z "$TOOL" ]; then
    TOOL="$HOME/.cache/novelnotifier-companion/appimagetool-x86_64.AppImage"
    if [ ! -x "$TOOL" ]; then
        echo ">> downloading appimagetool…"
        curl -fL -o "$TOOL" \
            "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
        chmod +x "$TOOL"
    fi
fi
# -u squashfs-root reuse off; ARCH pinned so the name matches the output.
ARCH=x86_64 "$TOOL" "$APPDIR" "$OUT"

echo ">> built: $OUT ($(du -h "$OUT" | cut -f1))"
