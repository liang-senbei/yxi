#!/usr/bin/env bash
set -euo pipefail
for command in python3 ffmpeg curl tar; do
  command -v "$command" >/dev/null || { echo "需要先安装 $command"; exit 1; }
done
HERE=$(cd "$(dirname "$0")" && pwd)
ASR_DIR="$HOME/.yxi/asr"
MODEL_URL='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2'
mkdir -p "$ASR_DIR" "$HOME/.local/bin"
if [ ! -x "$ASR_DIR/venv/bin/python3" ]; then
  python3 -m venv "$ASR_DIR/venv"
fi
if ! "$ASR_DIR/venv/bin/python3" -c 'import sherpa_onnx, numpy' >/dev/null 2>&1; then
  "$ASR_DIR/venv/bin/python3" -m pip install --disable-pip-version-check sherpa-onnx numpy
fi
if [ ! -s "$ASR_DIR/model/model.int8.onnx" ] || [ ! -s "$ASR_DIR/model/tokens.txt" ]; then
  echo '正在下载SenseVoice模型，下载包较大，请保持网络连接…'
  download_dir=$(mktemp -d "$ASR_DIR/download.XXXXXX")
  trap 'rm -rf -- "$download_dir"' EXIT
  curl -fL --retry 2 --max-time 1800 -o "$download_dir/model.tar.bz2" "$MODEL_URL"
  tar -xf "$download_dir/model.tar.bz2" -C "$download_dir"
  model_source="$download_dir/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
  test -s "$model_source/model.int8.onnx"
  test -s "$model_source/tokens.txt"
  if [ -e "$ASR_DIR/model" ]; then
    mv "$ASR_DIR/model" "$ASR_DIR/model.before-$(date +%s)"
  fi
  mv "$model_source" "$ASR_DIR/model"
  rm -f "$ASR_DIR/model/model.onnx"
fi
install -m 755 "$HERE/yxi-asr" "$HOME/.local/bin/yxi-asr"
echo '语音识别已安装，回到Yxi选择这台服务器即可录音。'
echo '首次识别需要加载模型，后续识别会复用服务。'
