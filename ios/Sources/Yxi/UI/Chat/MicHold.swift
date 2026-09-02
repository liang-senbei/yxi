import AVFoundation
import Speech
import SwiftUI

/// 按住说话。识别在**手机上**做（`SFSpeechRecognizer`，支持的语言一律离线），
/// 结果只交给调用方 —— **绝不直接发**：识别错一个字，在服务器上就是另一条命令。
///
/// 安卓那边为了做到「装了就能用」自己扛了一个 150MB 的模型（GMS 关着、系统没有识别器）；
/// iOS 系统自带的就是离线的、准的，**照搬那套模型纯属白扛**。
@MainActor
final class Dictation: ObservableObject {
    @Published private(set) var recording = false
    /// 松开之后等最终结果的那一小会儿
    @Published private(set) var busy = false

    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var latest = ""
    private var finished = false

    /// 开始录。返回出错原因；nil = 在录了。
    /// ⚠️ **一切失败都要说出来**：权限没给、麦克风被占 —— 静默失败的语音按钮废过一次（#152）。
    func start() async -> String? {
        guard await AVAudioApplication.requestRecordPermission() else {
            return "没给麦克风权限，用不了按住说话（设置 → Yxi 里打开）"
        }
        let auth = await withCheckedContinuation { c in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) }
        }
        guard auth == .authorized else { return "没给语音识别权限（设置 → Yxi 里打开）" }
        guard let rec = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN")) ?? SFSpeechRecognizer(),
              rec.isAvailable
        else { return "这台手机上语音识别不可用" }

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        // 能在手机上算就在手机上算 —— 不上传、不联网。不支持的语言才退回苹果的服务器。
        req.requiresOnDeviceRecognition = rec.supportsOnDeviceRecognition
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.record, mode: .measurement, options: .duckOthers)
            try s.setActive(true, options: .notifyOthersOnDeactivation)
            let input = engine.inputNode
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buf, _ in
                req.append(buf)
            }
            engine.prepare()
            try engine.start()
        } catch {
            return "录不了音：\(error.localizedDescription)"
        }
        latest = ""
        finished = false
        request = req
        task = rec.recognitionTask(with: req) { [weak self] r, err in
            Task { @MainActor in
                guard let self else { return }
                if let r { self.latest = r.bestTranscription.formattedString; if r.isFinal { self.finished = true } }
                if err != nil { self.finished = true }
            }
        }
        recording = true
        return nil
    }

    /// 松开。返回识别出的文字（最终结果要在 endAudio 之后一小会儿才到，最多等 2.5 秒）。
    func stop() async -> String {
        guard recording else { return "" }
        recording = false
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        busy = true
        for _ in 0..<25 where !finished {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        task?.cancel()
        task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        busy = false
        return latest.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// 按住说话的麦克风按钮。
///
/// ⚠️ **按住不是点一下。** 手指在 = 在录，松开就是说完了 —— 所有语音输入的通用手势。
/// ⚠️ `start` 失败时按钮不变红 —— 变了就是在骗人「我在录」。
/// ⚠️ 识别期间按钮变灰**且不可按**：再按一次会开一条新录音把上一条的结果冲掉。
struct MicHold: View {
    @ObservedObject var voice: Dictation
    let onText: (String) -> Void
    let onError: (String) -> Void
    @State private var down = false

    var body: some View {
        ZStack {
            Image(systemName: "mic.fill")
                .font(.system(size: 17))
                .foregroundStyle(voice.recording ? Yx.error : Yx.onSurfaceVar)
                .opacity(voice.busy ? 0 : 1)
            if voice.busy { ProgressView().controlSize(.small) }
        }
        .frame(width: 46, height: 46)
        .background(voice.recording ? Yx.errorBox : Yx.container, in: Circle())
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !down, !voice.busy else { return }
                    down = true
                    Task {
                        if let why = await voice.start() {
                            onError(why)
                            down = false
                        } else if !down {
                            // 权限框弹着的时候手指早抬了 —— 别让麦克风开着没人关
                            _ = await voice.stop()
                        }
                    }
                }
                .onEnded { _ in
                    down = false
                    Task {
                        let said = await voice.stop()
                        if !said.isEmpty { onText(said) }
                    }
                }
        )
    }
}
