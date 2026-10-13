#!/bin/bash
# Downloads the Sherpa-ONNX AAR (arm64-v8a only) and Kokoro TTS model files.
# Run from the project root: ./scripts/download-sherpa-onnx.sh
set -e
mkdir -p app/libs
if [ ! -f app/libs/sherpa-onnx.aar ]; then
  echo "Downloading sherpa-onnx v1.13.8 AAR..."
  curl -sL "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar" -o /tmp/sherpa-full.aar
  # Extract arm64-v8a only
  rm -rf /tmp/aar-slim && mkdir -p /tmp/aar-slim
  cd /tmp/aar-slim
  unzip -q /tmp/sherpa-full.aar
  rm -rf jni/x86_64 jni/armeabi-v7a jni/x86
  zip -q -r app/libs/sherpa-onnx.aar . -x ".*" "META-INF/*"
  cd - > /dev/null
  rm -rf /tmp/aar-slim /tmp/sherpa-full.aar
  echo "Done: $(du -sh app/libs/sherpa-onnx.aar | cut -f1)"
else
  echo "sherpa-onnx.aar already exists"
fi
