#!/usr/bin/env bash
# Cross-build a WINDOWS x64 zip of the NovelNotifier companion FROM LINUX.
#
# Why not jpackage: jpackage cannot produce Windows installers (or even
# app-images) on a Linux host, and Compose Multiplatform 1.9 dropped its old
# cross-platform packaging (-Pcompose.desktop.target.platform is silently
# ignored). CMP's own jars are OS-agnostic — only the skiko native differs —
# so this script assembles the same shape jpackage would:
#
#   NovelNotifierCompanion/
#     runtime/          Temurin 21 JDK for Windows (official Adoptium zip)
#     lib/app/*.jar     from the Linux distributable, Linux skiko swapped
#                       for skiko-awt-runtime-windows-x64 (Maven Central)
#     NovelNotifierCompanion.bat
#
# Usage:  companion/dev/build-windows-zip.sh
# Out:    companion/dist/NovelNotifierCompanion-<version>-windows-x64.zip
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep -oP 'packageVersion\s*=\s*"\K[^"]+' build.gradle.kts)"
APP_NAME="NovelNotifierCompanion"
SKIKO_VER="0.9.22.2"   # must match the skiko-awt jar the build resolves
CACHE="$HOME/.cache/novelnotifier-companion"
WORK="build/windows-zip"
IMG="build/compose/binaries/main/app/$APP_NAME"
OUT="dist/${APP_NAME}-${VERSION}-windows-x64.zip"

mkdir -p "$CACHE" "$WORK" dist

# 1) Linux app-image = the jar set (built on the Temurin daemon).
JVM_ARGS=()
TEMURIN="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"
[ -d "$TEMURIN" ] && JVM_ARGS+=("-Dorg.gradle.java.home=$TEMURIN")
./gradlew createDistributable "${JVM_ARGS[@]+${JVM_ARGS[@]}}"

rm -rf "$WORK/$APP_NAME"
mkdir -p "$WORK/$APP_NAME/lib/app"

# 2) Jars: everything from the Linux image except the Linux skiko native.
cp "$IMG"/lib/app/*.jar "$WORK/$APP_NAME/lib/app/" 2>/dev/null || true
rm -f "$WORK/$APP_NAME/lib/app/libskiko-linux-x64.so" \
      "$WORK/$APP_NAME/lib/app/libskiko-linux-x64.so.sha256"
cp "$IMG"/lib/app/NovelNotifierCompanion.cfg "$WORK/$APP_NAME/lib/app/" 2>/dev/null || true

# 3) Windows skiko native from Maven Central.
SKIKO_JAR="skiko-awt-runtime-windows-x64-$SKIKO_VER.jar"
if [ ! -f "$CACHE/$SKIKO_JAR" ]; then
    echo ">> downloading $SKIKO_JAR…"
    curl -fL -o "$CACHE/$SKIKO_JAR" \
        "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/$SKIKO_VER/$SKIKO_JAR"
fi
cp "$CACHE/$SKIKO_JAR" "$WORK/$APP_NAME/lib/app/"

# 4) Windows runtime: Temurin 21 JDK via Adoptium's stable redirect.
JDK_ZIP="$CACHE/temurin21-windows-x64.zip"
if [ ! -d "$CACHE/temurin21-windows-x64" ]; then
    if [ ! -f "$JDK_ZIP" ]; then
        echo ">> downloading Temurin 21 Windows x64 (~190MB, cached)…"
        curl -fL --max-time 900 -o "$JDK_ZIP" \
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
    fi
    mkdir -p "$CACHE/temurin21-windows-x64"
    unzip -q "$JDK_ZIP" -d "$CACHE/temurin21-windows-x64"
fi
RUNTIME_DIR="$(find "$CACHE/temurin21-windows-x64" -maxdepth 1 -mindepth 1 -type d | head -1)"
[ -n "$RUNTIME_DIR" ] || { echo "JDK zip layout unexpected"; exit 1; }
cp -r "$RUNTIME_DIR" "$WORK/$APP_NAME/runtime"
rm -rf "$WORK/$APP_NAME/runtime/jmods"   # jpackage-time only, ~60MB dead weight

# 5) Launcher. Full JDK on the classpath path (jshell/jcmd etc. ride along;
#    a trimmed runtime needs a Windows host to jlink, which we don't have).
cat > "$WORK/$APP_NAME/$APP_NAME.bat" <<'EOF'
@echo off
set DIR=%~dp0
"%DIR%runtime\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%DIR%lib\app\*" com.patchscratch.novelnotifier.companion.MainKt %*
EOF

# 6) Sanity: the two load-bearing pieces must be present and non-empty.
[ -f "$WORK/$APP_NAME/runtime/bin/java.exe" ] || { echo "missing java.exe"; exit 1; }
unzip -l "$WORK/$APP_NAME/lib/app/$SKIKO_JAR" | grep -q "\.dll" || { echo "skiko jar has no dll"; exit 1; }
ls "$WORK/$APP_NAME/lib/app/" | grep -q "^NovelNotifierCompanion-.*\.jar$" || { echo "app jar missing"; exit 1; }

echo ">> zipping…"
(cd "$WORK" && zip -qr9 "$OLDPWD/$OUT" "$APP_NAME")
echo ">> built: $OUT ($(du -h "$OUT" | cut -f1))"
echo "   (unzip on Windows, run $APP_NAME.bat — no Java install needed)"
