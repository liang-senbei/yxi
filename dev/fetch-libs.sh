#!/bin/sh
# 下载构建需要、但不适合进 git 的二进制依赖。
# 现在只有一个：sherpa-onnx 的安卓 AAR（47MB，手机端语音识别）。
set -eu
V=1.13.7
D="$(cd "$(dirname "$0")/../android/app/libs" && pwd)"
F="$D/sherpa-onnx.aar"
if [ -f "$F" ]; then echo "· 已经有了：$F"; exit 0; fi
mkdir -p "$D"
echo "· 下 sherpa-onnx $V 的 AAR（47MB）…"
curl -fL --progress-bar -o "$F.part" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-$V.aar"
mv "$F.part" "$F"
echo "· 好了：$(du -h "$F" | cut -f1)"
