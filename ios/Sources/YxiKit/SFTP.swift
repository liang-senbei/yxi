import Citadel
import Foundation
import NIOCore

/// 远端文件浏览（PRD 附录 G / D14）。
///
/// ⚠️ **走 SFTP，服务器上不装任何东西** —— SFTP 是 sshd 自带的子系统。
/// 这跟整个项目的取向一致：**能连 SSH 就能用**，目标机可能什么都没装（PRD 附录 H）。
///
/// ✅ **安卓那条锁在这里不需要了。** 安卓版每个操作都要 `Mutex`，因为
/// jsch 的 `ChannelSftp` 是一个有状态的对象，两个协程同时用会串包（#16 同类）。
/// Citadel 的 `SFTPClient` 用**单调递增的 request id + 按 id 配对响应**
/// （已读源码确认），并发请求是安全的。**别再照抄那把锁**，它只会白白把并行变串行。
public struct SFTP: Sendable {

    private let client: SFTPClient

    init(_ client: SFTPClient) { self.client = client }

    public struct Entry: Sendable, Hashable {
        public let name: String
        public let isDir: Bool
        public let size: Int64
        public let mtime: Date?
        /// 符号链接（[isDir] 已经是解引用之后的结果）
        public let isLink: Bool
    }

    public var isActive: Bool { client.isActive }

    public func close() async { try? await client.close() }

    /// 列一个目录。目录在前、再按名字排；`.` 和 `..` 不返回。
    public func list(_ path: String) async throws -> [Entry] {
        let pages = try await client.listDirectory(atPath: path)
        var out: [Entry] = []
        for page in pages {
            for c in page.components where c.filename != "." && c.filename != ".." {
                let mode = c.attributes.permissions
                var isDir = mode.map { $0 & 0o170000 == 0o040000 }
                let isLink = mode.map { $0 & 0o170000 == 0o120000 } ?? c.longname.hasPrefix("l")
                // ⚠️ SFTP v3 的属性位是**可选**的：服务器可以一个都不带。
                // 带不带全看实现，而「目录点不进去」这种毛病现场根本看不出原因，
                // 所以留一条从 `ls -l` 那串文字兜底的路。
                if isDir == nil { isDir = c.longname.hasPrefix("d") }

                // 符号链接指向目录时，readdir 给的是**链接自己**的属性 ——
                // 点进去才对，所以额外 stat 一次解引用。解不开（悬空链接）就当普通文件。
                var resolved = isDir ?? false
                if isLink {
                    let full = Paths.resolve(base: path, ref: c.filename)
                    resolved = (try? await client.getAttributes(at: full))
                        .flatMap { $0.permissions.map { $0 & 0o170000 == 0o040000 } } ?? false
                }
                out.append(
                    Entry(
                        name: c.filename,
                        isDir: resolved,
                        size: Int64(c.attributes.size ?? 0),
                        mtime: c.attributes.accessModificationTime?.modificationTime,
                        isLink: isLink
                    )
                )
            }
        }
        return out.sorted {
            $0.isDir != $1.isDir ? $0.isDir : $0.name.lowercased() < $1.name.lowercased()
        }
    }

    /// 读一个文件。
    ///
    /// - Parameter max: 上限。手机上没必要把几百兆的日志拉回来 —— 超了就截断，界面负责说明。
    ///
    /// ⚠️ **一次 `SFTPFile.read` 只发一个 READ 请求，拿回来的是一小块，不是整个文件。**
    /// （OpenSSH 每包大约几十 KB。）写成 `read(from: 0, length: max)` 就会**静默截断**，
    /// 表现是「大文件只显示开头一段」，而且没有任何报错。必须自己循环到 EOF。
    /// Citadel 的 `readAll()` 是循环的，但它**没有上限**，会把整个文件读进内存 —— 手机上不能用。
    public func read(_ path: String, max: Int = 2 << 20) async throws -> Data {
        try await client.withFile(filePath: path, flags: .read) { file in
            var out = Data()
            var offset: UInt64 = 0
            while out.count < max {
                let want = UInt32(Swift.min(64 * 1024, max - out.count))
                let chunk = try await file.read(from: offset, length: want)
                if chunk.readableBytes == 0 { break }
                out.append(contentsOf: chunk.readableBytesView)
                offset += UInt64(chunk.readableBytes)
            }
            return out
        }
    }

    /// 把远端文件**流式**写到本地文件。更新包 30 多 MB，没必要整个读进内存。
    /// - Returns: 实际字节数
    @discardableResult
    public func download(_ path: String, to url: URL, onProgress: @escaping @Sendable (Int64) -> Void = { _ in }) async throws -> Int64 {
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }

        return try await client.withFile(filePath: path, flags: .read) { file in
            var total: Int64 = 0
            while true {
                let chunk = try await file.read(from: UInt64(total), length: 64 * 1024)
                if chunk.readableBytes == 0 { break }
                try handle.write(contentsOf: Data(chunk.readableBytesView))
                total += Int64(chunk.readableBytes)
                onProgress(total)
            }
            return total
        }
    }

    /// 写一个文件（目录要先存在）。附件上传用（PRD 附录 F / D13）。
    public func write(_ path: String, bytes: Data) async throws {
        try await client.withFile(filePath: path, flags: [.write, .create, .truncate]) { file in
            try await file.write(ByteBuffer(bytes: bytes), at: 0)
        }
    }

    public func mkdirs(_ path: String) async throws {
        var cur = ""
        for seg in path.split(separator: "/") {
            cur += "/\(seg)"
            // 已存在就抛，忽略即可
            try? await client.createDirectory(atPath: cur)
        }
    }

    /// 把 `~`、`.`、`..` 这些解析成绝对路径。目标不存在会抛。
    public func realpath(_ path: String) async throws -> String {
        try await client.getRealPath(atPath: path)
    }

    public func isDir(_ path: String) async -> Bool {
        guard let mode = try? await client.getAttributes(at: path).permissions else { return false }
        return mode & 0o170000 == 0o040000
    }

    public func size(_ path: String) async -> Int64 {
        guard let s = try? await client.getAttributes(at: path).size else { return -1 }
        return Int64(s)
    }
}

/// 路径工具。
///
/// **纯字符串运算，可以单独测** —— 相对路径解析错了图片就显示不出来，
/// 而那种错误在界面上只表现为「图裂了」，很难定位。
/// （安卓 G7 的验收标准就是「带图的 README.md 里那张相对路径的图能显示出来」。）
public enum Paths {

    /// 目录部分（不含末尾斜杠）。`/a/b/c.md` → `/a/b`；没有斜杠时返回 `.`
    public static func dirOf(_ path: String) -> String {
        let trimmed = String(path.reversed().drop { $0 == "/" }.reversed())
        guard let i = trimmed.lastIndex(of: "/") else { return "." }
        if i == trimmed.startIndex { return "/" }
        return String(trimmed[trimmed.startIndex..<i])
    }

    public static func nameOf(_ path: String) -> String {
        let trimmed = String(path.reversed().drop { $0 == "/" }.reversed())
        guard let i = trimmed.lastIndex(of: "/") else { return trimmed }
        return String(trimmed[trimmed.index(after: i)...])
    }

    public static func extOf(_ path: String) -> String {
        let n = nameOf(path)
        guard let dot = n.lastIndex(of: "."), dot != n.startIndex else { return "" }
        return String(n[n.index(after: dot)...]).lowercased()
    }

    /// 把 [ref] 按 [base] 目录解析成绝对路径，并把 `.` / `..` 折叠掉。
    /// markdown 里的相对图片路径就靠它。绝对路径原样返回。
    public static func resolve(base: String, ref: String) -> String {
        if ref.hasPrefix("/") { return normalize(ref) }
        let b = String(base.reversed().drop { $0 == "/" }.reversed())
        return normalize(b + "/" + ref)
    }

    /// 折叠 `.` 和 `..`，去掉重复斜杠。
    public static func normalize(_ path: String) -> String {
        let abs = path.hasPrefix("/")
        var out: [String] = []
        for seg in path.split(separator: "/", omittingEmptySubsequences: true) {
            switch seg {
            case ".": continue
            case "..":
                if let last = out.last, last != ".." { out.removeLast() }
                else if !abs { out.append("..") }
            default: out.append(String(seg))
            }
        }
        let body = out.joined(separator: "/")
        if abs { return "/" + body }
        return body.isEmpty ? "." : body
    }

    /// 面包屑：`/a/b/c` → `[("/", "/"), ("a", "/a"), ("b", "/a/b"), ("c", "/a/b/c")]`
    public static func crumbs(_ path: String) -> [(name: String, path: String)] {
        var out: [(name: String, path: String)] = [("/", "/")]
        var acc = ""
        for seg in path.split(separator: "/", omittingEmptySubsequences: true) {
            acc += "/\(seg)"
            out.append((String(seg), acc))
        }
        return out
    }
}
