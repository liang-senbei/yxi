import Foundation
import YxiKit
#if canImport(Photos)
import Photos
#endif

/// 把远端文件存到手机上。安卓那边叫 `MediaSaver`，这是同一件事的 iOS 版。
///
/// ⚠️ **两边不可能一样，而且不是偷懒**：
/// 安卓有 MediaStore —— 图片进相册、别的进公共「下载」目录，App 写完就撒手。
/// iOS 根本没有「公共下载目录」这个东西，App 只能写自己的沙盒。所以这里分两路：
///
/// - **图片 / 视频 → 系统相册**（`PHPhotoLibrary`）。要 `NSPhotoLibraryAddUsageDescription`，
///   只申请「仅添加」权限：我们从不读用户的相册，不该要读的权限。
/// - **其它一切 → App 的 Documents**，靠 `UIFileSharingEnabled` 在「文件」App 里露出来，
///   再配一颗系统分享按钮送去 iCloud / 微信 / 任何地方。
enum SaveToPhone {

    enum Result {
        case photos          // 进相册了
        case documents(URL)  // 落在「文件」App 里，附上路径好分享
    }

    /// 这个文件名该走哪条路。跟安卓 `MediaSaver.mimeOf` 的分类保持一致。
    static func isMedia(_ name: String) -> Bool {
        let ext = Paths.extOf(name)
        return ["png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "tif", "tiff",
                "mp4", "mov", "m4v"].contains(ext)
    }

    static func isVideo(_ name: String) -> Bool {
        ["mp4", "mov", "m4v"].contains(Paths.extOf(name))
    }

    static func save(_ data: Data, as name: String) async throws -> Result {
        #if canImport(Photos)
        if isMedia(name), await requestAddOnly() {
            // 相册那套 API 只收文件 URL，先落到临时目录
            let tmp = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(name)
            try data.write(to: tmp, options: .atomic)
            defer { try? FileManager.default.removeItem(at: tmp) }
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                req.addResource(with: isVideo(name) ? .video : .photo, fileURL: tmp, options: nil)
            }
            return .photos
        }
        #endif
        // ⚠️ 相册没授权也要**存下来**，不能因为一个权限就什么都不给用户 ——
        // 退到 Documents 至少东西在手机上，用户在「文件」里找得到
        let dir = try FileManager.default.url(
            for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let url = dir.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return .documents(url)
    }

    #if canImport(Photos)
    private static func requestAddOnly() async -> Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if status == .authorized || status == .limited { return true }
        if status == .denied || status == .restricted { return false }
        return await withCheckedContinuation { cont in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { s in
                cont.resume(returning: s == .authorized || s == .limited)
            }
        }
    }
    #endif
}

