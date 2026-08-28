#!/usr/bin/env bash
# 推之前跑一遍。
#
# ⚠️ **为什么需要它**：`Sources/Yxi`（SwiftUI/UIKit）在 Linux 上编不了，
# 它的编译错误只有推到 CI 的 macOS runner 上才知道，一轮约 6 分钟。
# 下面这几条是**已经真的栽过**的错，用文本就能查出来 —— 别再拿 CI 当编译器。
set -u
cd "$(dirname "$0")/.." || exit 1
exec python3 dev/precheck.py
