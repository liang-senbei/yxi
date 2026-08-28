import SwiftUI
import YxiKit

/// 主机列表。**不是预置列表** —— 随时能加「以后才有的」服务器（PRD §2.4）：
/// 任意 IP、**任意端口**、任意用户名、密码或密钥。
///
/// ⚠️ **凡是能创建的东西，都要能改、能删。** 早先安卓版只有「加」：
/// 点一下是连接、长按是装公钥，于是地址打错或要换端口时用户**一点办法都没有**
/// （连删都删不掉，只能卸载重装，密钥一起丢）。而「地址里混进全角字符」
/// 恰恰是最常见的一种错 —— 我们既提示了「删掉重打」，就得真有地方能删。
/// 见 TROUBLESHOOTING #68。
@MainActor
struct HostsScreen: View {

    let hosts: [Host]
    let keys: KeyManager
    /// 一键装公钥要用它。没有就把「装公钥」按钮藏掉，不放一个按了没反应的按钮
    var installer: (any KeyInstalling)? = nil

    var onOpen: (Host) -> Void
    var onSave: (Host) -> Void
    /// ⚠️ 数据层删完要顺手同步后台盯梢 —— 删掉的可能正是唯一开着铃铛的那台
    var onDelete: (String) -> Void
    var onSetWatch: (String, Bool) -> Void

    @State private var editing: Host?
    @State private var adding = false
    @State private var showKey = false
    @State private var installTarget: Host?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text("主机").font(.system(size: 24, weight: .semibold)).foregroundStyle(Yx.onSurface)
                Spacer()
                pill("公钥") { showKey = true }
                pill("＋", primary: true) { adding = true }
            }
            .padding(.horizontal, Yx.pad).padding(.top, 14).padding(.bottom, 12)

            if hosts.isEmpty {
                Spacer()
                Text("还没有主机\n点右上角 ＋ 加一台")
                    .font(.system(size: 15))
                    .foregroundStyle(Yx.dim)
                    .multilineTextAlignment(.center)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 9) {
                        ForEach(hosts) { h in
                            HostRow(
                                h: h,
                                keys: keys,
                                onOpen: { onOpen(h) },
                                onEdit: { editing = h },
                                onWatch: { onSetWatch(h.id, !h.watch) }
                            )
                        }
                        // ⚠️ **长按是隐藏手势，必须有地方写出来**（#68 的教训：
                        // 改端口的入口藏在长按里，用户根本找不到）。所以这里明写一句。
                        // ⚠️ YxHint 收的是 String 变量，`Text(变量)` **不走 markdown**
                        // （只有字符串字面量才会）—— 这里写 `**粗体**` 只会原样打出星号。
                        YxHint("长按一台主机看它的订阅额度 —— 5 小时窗和本周各用掉了几成。")
                            .padding(.horizontal, 4).padding(.top, 14)
                        // ⚠️ 铃铛在 iOS 上只有前台盯梢 —— 没有前台服务，进后台约 30 秒 socket 就被收走。
                        // 文案必须**当面说清**，不能让用户以为锁屏了也会响（PRD §2.7 已否掉推送）。
                        YxHint("🔔 只在 Yxi 开着的时候盯 —— iOS 不允许后台常驻连接，锁屏或切走就不会响了。")
                            .padding(.horizontal, 4).padding(.top, 8)
                    }
                    .padding(.horizontal, 14).padding(.bottom, 18)
                }
            }
        }
        .background(Yx.surface)
        .sheet(isPresented: $adding) {
            HostEditor(editing: nil, canInstallKey: false, onSave: onSave, onDelete: { _ in }, onInstallKey: {})
        }
        .sheet(item: $editing) { h in
            HostEditor(
                editing: h,
                canInstallKey: installer != nil,
                onSave: onSave,
                onDelete: onDelete,
                onInstallKey: { editing = nil; installTarget = h }
            )
        }
        .sheet(isPresented: $showKey) { PublicKeySheet(keys: keys) }
        .sheet(item: $installTarget) { h in
            if let ins = installer {
                InstallKeySheet(host: h, installer: ins, onSave: onSave)
            }
        }
    }

    private func pill(_ label: String, primary: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            YxPill(fill: primary ? Yx.copper : Yx.container, hPad: 18) {
                Text(label)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(primary ? Yx.onCopper : Yx.onSurface)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 一行

@MainActor
private struct HostRow: View {
    let h: Host
    /// 查额度要现连一次 —— 见 [loadQuota]
    let keys: KeyManager
    let onOpen: () -> Void
    let onEdit: () -> Void
    let onWatch: () -> Void

    /// 默认什么都不画，长按才展开（跟安卓一致）
    @State private var quotaOpen = false
    @State private var quota: Quota?
    /// 这份数字什么时候取的。⚠️ **必须显示** —— 缓存值和刚取的值长得一模一样，
    /// 不写时间的话用户没法判断「到底刷新了没有」。
    @State private var quotaAt: Date?
    @State private var quotaNote: String?
    @State private var quotaBusy = false

    var body: some View {
        YxCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(h.alias)
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(Yx.onSurface)
                        // ⚠️ 地址栏里混进中文/全角字符是**最贵的一种错**：连不上，
                        // 而错误信息在别处 —— 用户看着列表觉得一切正常。所以在列表里就标出来。
                        // 安卓上那台主机的地址是「天亮」（一个 SSH 别名），在列表里躺了好几天没人发现。
                        let bad = h.suspiciousCharacter
                        HStack(spacing: 8) {
                            Text(h.display)
                                .font(.mono(13))
                                .foregroundStyle(bad != nil ? Yx.error : Yx.dim)
                            // 「密钥 / 密码」是**信息不是动作**，所以跟地址在一起，
                            // 右边只留铃铛和 ⋯ —— 一行上挤三个胶囊会把地址压到折行
                            Text(h.useKey ? "密钥" : "密码")
                                .font(.system(size: 11))
                                .foregroundStyle(h.useKey ? Yx.onCopperBox : Yx.dim)
                                .padding(.horizontal, 9).padding(.vertical, 3)
                                .background(h.useKey ? Yx.copperBox : Yx.high, in: Capsule())
                        }
                        if let bad {
                            Text("⚠️ 地址里有 \(bad) —— 连不上。点右边 ⋯ 改。")
                                .font(.system(size: 11))
                                .foregroundStyle(Yx.error)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    // ⚠️ 轻点 = 连接、长按 = 看额度，**两个手势挂在同一个 view 上、
                    // 顺序也照抄 [CornerKey]（DPad.swift）那份已经在真机上跑过的**。
                    // 安卓 #124 是这条的反面教材：`clickable` 不认「长按」这回事，
                    // 长按原地松手照样发 onClick，于是长按一次就误开一次对话。
                    .onTapGesture(perform: onOpen)
                    .onLongPressGesture { toggleQuota() }

                    // 铃铛：让手机为这台机器主动响。⚠️ 需要那台机器上装了 yxi-hook
                    Button(action: onWatch) {
                        Text(h.watch ? "🔔" : "🔕")
                            .font(.system(size: 12))
                            .padding(.horizontal, 11).padding(.vertical, 6)
                            .background(h.watch ? Yx.copperBox : Yx.high, in: Capsule())
                    }
                    .buttonStyle(.plain)

                    // ⚠️ 改 / 删的入口必须**看得见**。安卓那版藏在长按里，
                    // 结果用户要改端口时找不到任何入口（#68）—— 隐藏手势不算入口
                    Button(action: onEdit) {
                        Text("⋯")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Yx.muted)
                            .frame(width: 36, height: 32)
                            .background(Yx.high, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                // 用量细线。⚠️ 没缓存过就什么都不画 —— 不为了这条线去连每一台机器
                UsageStrip(hostId: h.id)
                if quotaOpen { quotaPanel }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
        }
        // ⚠️ **原来这里有个 `.contextMenu`（连接 / 改主机），拿掉了。**
        // iOS 上 `.contextMenu` 吃的就是长按，跟上面那个 `.onLongPressGesture`
        // 抢同一个手势 —— 一张卡上挂两个长按消费者必然打架（安卓 #124 同一个病）。
        // 它那两项本来也是重复的入口：轻点就是「连接」，右边 `⋯` 就是「改主机」，
        // 而 #68 的结论正是**入口要看得见**，藏在长按里的那份不算数。
    }

    // MARK: - 订阅额度

    /// ⚠️⚠️ **这跟上面那条 [UsageStrip] 不是一回事**（TROUBLESHOOTING #117 / #122）：
    /// 那条是 `ccusage` 从本地会话日志算出来的 **token 数和花的钱**；
    /// 这里是**账号级的订阅配额** ——「这个 5 小时窗还剩多少、本周还剩多少」。
    /// 两个数各说各的，谁也代替不了谁。
    ///
    /// ⚠️ **拿不到就整块不画**，不显示 0 也不显示「未知」（#51）——
    /// 但**必须把原因写出来**：看不见又不说为什么，等于让人对着空白干等。
    @ViewBuilder
    private var quotaPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let q = quota {
                // 档位 + 工具名，学 Moshi 那个「Max 20x · Claude Code」的头。
                // ⚠️ 档位读不到（credentials 里没有那两个字段）就只写工具名，
                // **不写「未知档位」** —— 少一个小标签而已，额度照显示。
                HStack(spacing: 6) {
                    if !q.plan.isEmpty {
                        Text(q.plan)
                            .font(.system(size: 11))
                            .foregroundStyle(Yx.onCopperBox)
                            .padding(.horizontal, 9).padding(.vertical, 3)
                            .background(Yx.copperBox, in: Capsule())
                    }
                    Text("Claude Code").font(.system(size: 11)).foregroundStyle(Yx.muted)
                }
                quotaBar("5 小时", percent: q.sessionPercent, resets: q.sessionResets)
                quotaBar("本周", percent: q.weekPercent, resets: q.weekResets)
            }
            if quotaBusy {
                Text("查着…").font(.system(size: 11)).foregroundStyle(Yx.dim)
            } else if let quotaAt {
                Text(ago(quotaAt) + " 取的").font(.system(size: 11)).foregroundStyle(Yx.dim)
            } else if let note = quotaNote {
                Text(note)
                    .font(.system(size: 11))
                    .foregroundStyle(Yx.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 12)
    }

    /// 一档额度：`5 小时 ████░░░░ 已用 11% · Aug 28, 5:19pm (UTC) 重置`。
    /// ⚠️ 进度条用 `GeometryReader` 量宽度 —— 照抄 [UsageCard] 里那个 `bar`，
    /// 按比例给 `.frame(width:)` 得等布局才知道底数。
    private func quotaBar(_ label: String, percent: Int, resets: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(label).font(.system(size: 11)).foregroundStyle(Yx.muted)
                Spacer()
                // ⚠️ 百分比是这块唯一非看不可的数，给它 layoutPriority，
                // 别在窄屏上被后面那串重置时间挤掉
                Text("已用 \(percent)%")
                    .font(.mono(11))
                    .foregroundStyle(percent > 85 ? Yx.amber : Yx.onSurface)
                    .layoutPriority(1)
                if !resets.isEmpty {
                    Text("· \(resets) 重置")
                        .font(.mono(10))
                        .foregroundStyle(Yx.dim)
                        .lineLimit(1)
                }
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Yx.high)
                    Capsule()
                        .fill(percent > 85 ? Yx.amber : Yx.teal)
                        .frame(width: geo.size.width * CGFloat(min(max(percent, 0), 100)) / 100)
                }
            }
            .frame(height: 5)
        }
    }

    /// 长按：开合额度块。
    ///
    /// ⚠️ **每次展开都重查一遍，旧值先摆着**（安卓那边同样的做法）：
    /// 额度是会动的，展开时看到的必须是刚拿到的；但也不能把界面清空让人对着空白等。
    private func toggleQuota() {
        // 时长照抄 [ToolCards] 里那个展开动画，全 App 一个手感
        withAnimation(.easeInOut(duration: 0.18)) { quotaOpen.toggle() }
        // ⚠️ 每次长按展开都重查一次（用户明确要的：「长按服务器就更新一次用量」）。
        // `!quotaBusy` 只挡「上一次还没回来就又点」，不挡「重新展开」。
        guard quotaOpen, !quotaBusy else { return }
        Task { await loadQuota() }
    }

    /// **现连现查**：主机页平时不为每台机器保一条连接（那样打开 App 就要建 N 条 SSH）。
    /// 长按这一下才连一次、跑一条 `claude -p '/usage'`、拿完就断。
    /// 不借会话、不往任何 tmux 面板送键、不花钱（#117）。
    private func loadQuota() async {
        quotaBusy = true
        quotaNote = nil
        defer { quotaBusy = false }

        // ⚠️⚠️ **只查已经信任过指纹的主机，这里绝不做 TOFU。**
        // 首连要用户当面核对指纹再拍板，那是「会话」页那条路的事；
        // 在一个查额度的小面板里悄悄记下一把没人看过的主机公钥，
        // 等于把 SSH 唯一那道防线降级成「反正也没人看」（#24 / #22）。
        guard let knownKey = h.hostKey else {
            quotaNote = "还没连过这台主机 —— 先点一下连上、核对指纹，再来看额度。"
            return
        }
        guard let cfg = h.config(privateKey: { try? keys.privateKey() },
                                 unseal: { Vault.open($0) }) else {
            quotaNote = "这台主机还没填密码，也没装公钥 —— 去右边 ⋯ 补一个。"
            return
        }
        let session = SSHSession(config: cfg, gate: HostKeyGate(
            target: cfg.target,
            stored: { knownKey },
            // 这条连接只读不写：主机表由「会话」页那条连接负责维护
            remember: { _ in },
            prompt: .denyEverything
        ))
        do {
            try await session.connect()
            defer { Task { await session.disconnect() } }
            let out = try await session.exec(Quota.probeCommand).stdout
            let (got, why) = Quota.read(out)
            if let got {
                quota = got
                quotaAt = Date()      // 「什么时候取的」要看得见，否则刷没刷分不出来
            } else {
                // ⚠️ 说清楚是**为什么**没有，别只留一块空白。
                // ⚠️ **说真原因，别用「多半是」蒙。** 猜错的原因比不说更糟：
                // 用户会照着去做无用功（比如去关会话、去重登账号）。
                quotaNote = why?.text ?? "这台机器上查不到订阅额度。"
            }
        } catch is CancellationError {
            // ⚠️ 取消不是失败 —— 别把它写成一句钉在界面上的假错误（#78 / #79）
        } catch {
            // ⚠️ 报错里用 `cfg.target`（真正连的地址），**不是别名**（#71）
            quotaNote = Explain.connection(error, target: cfg.target, hostname: h.hostname)
        }
    }
}
