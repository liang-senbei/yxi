import SwiftUI
import UIKit
import YxiKit

/// 用户消息里那些附件的缩略图。跟安卓 `AttachThumb.kt` 一个规矩。
///
/// ⚠️ **图在服务器上，得拉回来。** 发送那一刻本地有（[seed] 先种进缓存，第一帧就是图），
/// 重进对话是从转录读的，那时只剩路径 —— 走 SFTP 读回来，**按远端路径缓存到本地**，
/// 同一张只拉一次。
/// ⚠️ **拉不到不是错误。** 暂存区 3 天就清，老消息里的图必然拉不到 —— 安静地显示文件名。
@MainActor
enum Thumbs {
    private enum Slot { case have(UIImage), missing }
    /// 内存里再挡一层：同一屏里同一张图会被多次组合。
    private static var mem: [String: Slot] = [:]

    /// 缓存在 Caches/thumb/<路径的哈希>.jpg。
    /// ⚠️ 自己算哈希 —— `String.hashValue` 每次启动都换种子，缓存等于没存。
    private static func cacheFile(_ remote: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("thumb", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var h: UInt64 = 5381
        for b in remote.utf8 { h = (h &* 33) &+ UInt64(b) }
        return dir.appendingPathComponent(String(h, radix: 16) + ".jpg")
    }

    /// 发送那一刻把**本地那份**种进缓存 —— 图刚从这台手机传上去，没理由再拉一遍。
    static func seed(_ remote: String, data: Data) {
        guard mem[remote] == nil, let img = shrink(data) else { return }
        mem[remote] = .have(img)
        try? img.jpegData(compressionQuality: 0.82)?.write(to: cacheFile(remote))
    }

    static func load(_ files: FileService?, _ remote: String) async -> UIImage? {
        if let s = mem[remote] { if case let .have(i) = s { return i } else { return nil } }
        let f = cacheFile(remote)
        if let d = try? Data(contentsOf: f), let img = UIImage(data: d) {
            mem[remote] = .have(img)
            return img
        }
        // ⚠️ 有上限：手机拍的原图动辄十几 MB，全读回来只为画 112pt 的缩略图不值当
        guard let files, let data = try? await files.readFile(remote, max: 8 << 20),
              let img = await Task.detached(priority: .userInitiated, operation: { shrink(data) }).value
        else { mem[remote] = .missing; return nil }
        try? img.jpegData(compressionQuality: 0.82)?.write(to: f)
        mem[remote] = .have(img)
        return img
    }

    /// 长边缩到 480 —— 原图直接进内存就是几十 MB，几张就爆。
    nonisolated private static func shrink(_ data: Data) -> UIImage? {
        guard let img = UIImage(data: data) else { return nil }
        let m = max(img.size.width, img.size.height)
        guard m > 480 else { return img }
        let s = 480 / m
        return img.preparingThumbnail(of: CGSize(width: img.size.width * s, height: img.size.height * s)) ?? img
    }
}

/// 一排缩略图，在气泡**外面上方** —— 放进气泡里一张竖图会把气泡撑成一条。
struct ThumbRow: View {
    let refs: [Attachments.Ref]
    let files: FileService?
    let onOpen: (Attachments.Ref) -> Void
    var body: some View {
        HStack(spacing: 6) {
            ForEach(refs, id: \.path) { AttachThumb(ref: $0, files: files, onOpen: onOpen) }
        }
    }
}

/// 一张附件缩略图。图片画图，别的画一个带文件名的小卡片。点开是看原图，不是看路径。
struct AttachThumb: View {
    let ref: Attachments.Ref
    let files: FileService?
    let onOpen: (Attachments.Ref) -> Void
    @State private var img: UIImage?
    @State private var tried = false

    var body: some View {
        if ref.isImage {
            ZStack {
                if let img {
                    Image(uiImage: img).resizable().scaledToFill()
                } else if !tried {
                    ProgressView().controlSize(.small)
                } else {
                    // 拉不到就写文件名 —— 不画碎图标，3 天前的图被清掉是正常的
                    Text(String(ref.name.suffix(14)))
                        .font(.system(size: 11)).foregroundStyle(Yx.dim).padding(8)
                }
            }
            .frame(width: 112, height: 112)
            .background(Yx.high)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
            .onTapGesture { if img != nil { onOpen(ref) } }
            .task(id: ref.path) {
                img = await Thumbs.load(files, ref.path)
                tried = true
            }
        } else {
            Button { onOpen(ref) } label: {
                Text(ref.name)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Yx.onSurfaceVar)
                    .lineLimit(1)
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .background(Yx.high, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }
}
