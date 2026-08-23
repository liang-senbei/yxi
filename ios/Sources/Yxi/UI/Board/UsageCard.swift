import SwiftUI
import YxiKit

/// 用量的**缓存**。
///
/// ⚠️ 主机列表上不为了显示用量去连每一台机器 —— 那样打开 App 就要建 N 条 SSH 连接。
/// 会话看板本来就连着，在那儿探一次、存下来；列表读缓存并标出**是多久以前的**。
/// **显示一个不标时间的旧数字，跟显示假数字是一个性质**（TROUBLESHOOTING #51）。
enum UsageCache {
    private struct Cached: Codable { let usage: Usage; let at: Date }

    static func put(hostId: String, _ u: Usage) {
        guard let data = try? JSONEncoder().encode(Cached(usage: u, at: Date())) else { return }
        UserDefaults.standard.set(data, forKey: "usage:\(hostId)")
    }

    /// - Returns: (用量, 距现在多少分钟)；没缓存返回 nil
    static func get(hostId: String) -> (Usage, Int)? {
        guard let data = UserDefaults.standard.data(forKey: "usage:\(hostId)"),
              let c = try? JSONDecoder().decode(Cached.self, from: data) else { return nil }
        return (c.usage, Int(Date().timeIntervalSince(c.at) / 60))
    }
}

/// 主机列表上的一条细线。**没有数据就什么都不画** —— 调用方不用判断。
struct UsageStrip: View {
    let hostId: String

    var body: some View {
        if let (u, age) = UsageCache.get(hostId: hostId) {
            HStack(spacing: 8) {
                bar(u, height: 4)
                Text("剩 \(u.remainText)" + (age > 30 ? " · \(age / 60)h 前" : ""))
                    .font(.mono(11))
                    .foregroundStyle(Yx.dim)
            }
            .padding(.top, 8)
        }
    }
}

/// 会话看板顶上的详情卡。同样：没数据就什么都不画。
///
/// ⚠️ **探不到 `ccusage` 就整块藏起来**：不显示 0，不显示「未知」，不画空进度条。
/// 额度这种数字**你会照着它安排今天开不开大活** —— 一个假的 0 会让你以为额度还很多，
/// 一个假的满格会让你不敢干活，两种都比看不见糟（TROUBLESHOOTING #51）。
struct UsageCard: View {
    let u: Usage
    init(_ u: Usage) { self.u = u }

    var body: some View {
        YxCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("5 小时窗口")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(u.elapsedPercent > 85 ? Yx.amber : Yx.muted)
                    Spacer()
                    Text("距重置 \(u.remainText)")
                        .font(.mono(12, weight: .medium))
                        .foregroundStyle(Yx.onSurface)
                }
                bar(u, height: 6)
                Text(
                    "\(u.tokenText) token · $" + String(format: "%.2f", u.costUSD)
                        + (u.tokensPerMinute > 0 ? " · \(Int(u.tokensPerMinute))/分" : "")
                )
                .font(.mono(11))
                .foregroundStyle(Yx.dim)
            }
            .padding(.horizontal, 16).padding(.vertical, 13)
        }
    }
}

/// 进度条。⚠️ 用 GeometryReader 而不是 `.frame(width: 比例 * ?)` —— 宽度要等布局才知道。
@ViewBuilder
private func bar(_ u: Usage, height: CGFloat) -> some View {
    GeometryReader { geo in
        ZStack(alignment: .leading) {
            Capsule().fill(Yx.high)
            Capsule()
                .fill(u.elapsedPercent > 85 ? Yx.amber : Yx.teal)
                .frame(width: geo.size.width * CGFloat(min(max(u.elapsedPercent, 0), 100)) / 100)
        }
    }
    .frame(height: height)
}
