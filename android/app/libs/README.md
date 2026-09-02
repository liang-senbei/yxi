# 这里放 sherpa-onnx 的安卓 AAR

**不进 git**（47MB）。缺了的话跑：

```bash
dev/fetch-libs.sh
```

它下的是 `sherpa-onnx-<版本>.aar`（官方 release），提供手机端语音识别的原生库和
Kotlin API。构建时只打包 `arm64-v8a` 那一套（见 `app/build.gradle.kts` 的 `abiFilters`）。

模型不在这儿 —— 太大（153MB），首次用语音时从 `yxi.keuury.com/asr/` 下到手机上。
