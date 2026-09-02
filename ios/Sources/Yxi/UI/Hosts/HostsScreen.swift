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
                        // ⚠️ 体检写在前面是因为它**先出来**（1 秒 vs 十几秒），
                        // 提示语的顺序得跟面板里真实的顺序一致，否则等的人会以为卡住了。
                        YxHint("长按一台主机：先出一份服务器体检（1 秒），再是订阅额度 —— 5 小时窗和本周各用掉了几成。")
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
    @State private var panelOpen = false
    @State private var quota: Quota?
    /// 这份数字什么时候取的。⚠️ **必须显示** —— 缓存值和刚取的值长得一模一样，
    /// 不写时间的话用户没法判断「到底刷新了没有」。
    @State private var quotaAt: Date?
    @State private var quotaNote: String?
    @State private var quotaBusy = false

    /// 体检的一份报告。⚠️ 它和上面那几个额度状态**是两套、互不等待**（见 [togglePanel]）。
    @State private var health: Health.Report?
    @State private var healthBusy = false
    @State private var healthNote: String?
    /// 扫出来的「可以安全收掉的东西」。nil = 还没扫；空表 = 没什么可收的
    @State private var junk: [Health.Junk]?
    @State private var fixOpen = false
    /// 勾了哪几**类**要收拾。⚠️ 每次开框都清空 —— 这是杀进程，
    /// 让人主动勾比让人记得取消安全。
    @State private var picked: Set<String> = []
    @State private var fixing = false

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
                    // ⚠️ 轻点 = 连接、长按 = 体检 + 额度，**两个手势挂在同一个 view 上、
                    // 顺序也照抄 [CornerKey]（DPad.swift）那份已经在真机上跑过的**。
                    // 安卓 #124 是这条的反面教材：`clickable` 不认「长按」这回事，
                    // 长按原地松手照样发 onClick，于是长按一次就误开一次对话。
                    .onTapGesture(perform: onOpen)
                    .onLongPressGesture { togglePanel() }

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
                if panelOpen {
                    // ⚠️ **体检在额度前面**，因为它 1.1 秒就出数、额度要十几秒。
                    // 反过来的话，先画出来的那块会把后出的顶下去，看着像在跳。
                    healthPanel
                    quotaPanel
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
        }
        .sheet(isPresented: $fixOpen) { fixSheet }
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
                VStack(alignment: .leading, spacing: 8) {
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
                // ⚠️ **查新的时候把旧数字压暗。** 缓存值和实时值长得一模一样，
                // 满色画着的话，用户分不出面板上这两根条是刚取的还是上次的。
                //
                // ⚠️⚠️ **修饰符必须贴在视图上，不能贴在 `if` 的右花括号后面。**
                // 我原来就是那么写的（`}` 换行再 `.opacity(...)`），
                // ViewBuilder 里那等于「对 View 这个类型调实例方法」，
                // 报 `instance member 'opacity' cannot be used on type 'View'`。
                // 而**这个错误在 Linux 上永远发现不了**（SwiftUI 编译不了，
                // precheck 只做语法解析），iOS 从那次改动起就一直编不过。
                .opacity(quotaBusy ? 0.4 : 1)
            }
            // ⚠️ 固定高度是为了**不跳** —— 转圈那会儿比一行字高，查完塌回去会闪一下。
            HStack(spacing: 8) {
                if quotaBusy {
                    // 查额度慢是常态（`claude -p '/usage'` 要连一次 API，几秒到十几秒）。
                    // 原来只有一行灰字「查着…」，太轻了，用户看不出在动。
                    ProgressView().controlSize(.small)
                    Text("在取实时额度…").font(.system(size: 11)).foregroundStyle(Yx.dim)
                // ⚠️ **先判 note 再判 quotaAt**，别反过来。反过来的话
                // 「有缓存 + 这次刷新失败」只会画出时间戳，错误被**整个吞掉** ——
                // 用户以为刷成功了，其实盯着的还是旧数字。
                } else if let note = quotaNote {
                    Text(note)
                        .font(.system(size: 11))
                        .foregroundStyle(Yx.dim)
                        .fixedSize(horizontal: false, vertical: true)
                } else if let quotaAt {
                    Text(ago(quotaAt) + " 取的").font(.system(size: 11)).foregroundStyle(Yx.dim)
                }
            }
            .frame(minHeight: 22)
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

    /// 长按：开合这块面板。
    ///
    /// ⚠️ **每次展开都重查一遍，旧值先摆着**（安卓那边同样的做法）：
    /// 额度是会动的，展开时看到的必须是刚拿到的；但也不能把界面清空让人对着空白等。
    ///
    /// ⚠️⚠️ **体检和额度各起各的 Task、各连各的，绝不串在一条链上。**
    /// 体检只读 `/proc` 一秒出数，额度要跑 `claude -p '/usage'` 连一次 API（十几秒）——
    /// 串起来就是让体检等额度，而**机器越卡，越是只有体检出得来**，
    /// 那正好是最需要先看见它的时候。
    private func togglePanel() {
        // 时长照抄 [ToolCards] 里那个展开动画，全 App 一个手感
        withAnimation(.easeInOut(duration: 0.18)) { panelOpen.toggle() }
        // ⚠️ 每次长按展开都重查一次（用户明确要的：「长按服务器就更新一次用量」）。
        // `!busy` 只挡「上一次还没回来就又点」，不挡「重新展开」。
        guard panelOpen else { return }
        if !healthBusy { Task { await loadHealth() } }
        if !quotaBusy { Task { await loadQuota() } }
    }

    /// 连一条**只读**的临时连接：连上、跑完就断。体检和额度各建各的。
    ///
    /// ⚠️⚠️ **只连已经信任过指纹的主机，这里绝不做 TOFU。**
    /// 首连要用户当面核对指纹再拍板，那是「会话」页那条路的事；
    /// 在一个查数字的小面板里悄悄记下一把没人看过的主机公钥，
    /// 等于把 SSH 唯一那道防线降级成「反正也没人看」（#24 / #22）。
    ///
    /// ⚠️ 第二个返回值是**要贴到界面上的那句话**（体检和额度各贴各的地方）。
    /// 两个都是 nil = 用户取消了，界面上什么都别写（#78 / #79）。
    private func openSession() async -> (SSHSession?, String?) {
        guard let knownKey = h.hostKey else {
            return (nil, "还没连过这台主机 —— 先点一下连上、核对指纹，再回来看。")
        }
        guard let cfg = h.config(privateKey: { try? keys.privateKey() },
                                 unseal: { Vault.open($0) }) else {
            return (nil, "这台主机还没填密码，也没装公钥 —— 去右边 ⋯ 补一个。")
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
            return (session, nil)
        } catch is CancellationError {
            return (nil, nil)
        } catch {
            // ⚠️ 报错里用 `cfg.target`（真正连的地址），**不是别名**（#71）
            return (nil, Explain.connection(error, target: cfg.target, hostname: h.hostname))
        }
    }

    /// **现连现查**：主机页平时不为每台机器保一条连接（那样打开 App 就要建 N 条 SSH）。
    /// 长按这一下才连一次、跑一条 `claude -p '/usage'`、拿完就断。
    /// 不借会话、不往任何 tmux 面板送键、不花钱（#117）。
    private func loadQuota() async {
        quotaBusy = true
        quotaNote = nil
        defer { quotaBusy = false }

        // ⚠️ 这里的 `note` 不能叫 `why` —— 下面 `Quota.read` 也吐一个 `why`，
        // 重名会在同一段里指两个东西
        let (opened, note) = await openSession()
        guard let session = opened else { quotaNote = note; return }
        defer { Task { await session.disconnect() } }
        do {
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
            // ⚠️ 报错里用 `session.target`（真正连的地址），**不是别名**（#71）
            quotaNote = Explain.connection(error, target: session.target, hostname: h.hostname)
        }
    }

    // MARK: - 服务器体检

    /// 分数 + 那五个数 + 扣在哪儿 + 「可以收拾」。
    ///
    /// ⚠️ 起因是 2026-08-29 那次：服务器卡到敲命令都要等几秒，而第一轮诊断
    /// **只看了 load 和内存、漏了 CPU steal**，于是得出一个自洽但不完整的结论。
    /// 所以那五个数**一个都不能省**，尤其「被抢」——`uptime` / `free` 里根本没有它。
    @ViewBuilder
    private var healthPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let r = health {
                // 分数按档变色，扫一眼不用读字：绿 → 黄 → 橙 → 红
                let tone = scoreTone(r.score)
                HStack(spacing: 12) {
                    HStack(alignment: .bottom, spacing: 0) {
                        Text("\(r.score)")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(tone)
                        Text(" /100")
                            .font(.system(size: 11))
                            .foregroundStyle(Yx.dim)
                            .padding(.bottom, 5)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(verdictText(r.score))
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(tone)
                        Text(vitalsLine(r.vitals))
                            .font(.mono(11))
                            .foregroundStyle(Yx.dim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                // 扣分理由。⚠️ **不 fixable 的要明说「修不了」** ——
                // 否则用户按了一键收拾没反应，只会更困惑（steal 在虚拟机里面无解）。
                ForEach(r.issues, id: \.code) { i in
                    HStack(alignment: .top, spacing: 6) {
                        Text("−\(i.cost)")
                            .font(.mono(11))
                            .foregroundStyle(Yx.dim)
                        Text(issueText(i) + (i.fixable ? "" : "（机器里面修不了，得找服务商）"))
                            .font(.system(size: 12))
                            .foregroundStyle(i.fixable ? Yx.muted : Yx.error)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                // 一键收拾：扫出东西才画这个按钮，没东西可收就别放一个按了没用的
                if let list = junk, !list.isEmpty {
                    // 按**类别**报数，跟点进去看到的一致 —— 外面说「3 项」进去却是 1 组，对不上。
                    // ⚠️ 字串先算好再进 `Text`：插值套 `map`/`Set` 塞在 ViewBuilder 里，
                    // 正是把 SwiftUI 的类型检查器拖到超时的那种写法。
                    let label = fixing
                        ? "收拾中…"
                        : "可以收拾 \(Set(list.map(\.what)).count) 类 · 约 \(totalMb(list)) MB"
                    Button {
                        // ⚠️ 每次开框都清空勾选 —— 这是杀进程，默认全不选
                        picked = []
                        fixOpen = true
                    } label: {
                        YxPill(fill: Yx.copperBox) {
                            Text(label)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Yx.onCopperBox)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(fixing)
                }
            }
            // ⚠️ 只在**还没有任何报告**时画转圈：重查时上面那份旧的照样看得见，
            // 别为了显示「在忙」把已经有的数字清掉。
            if healthBusy && health == nil {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("在体检…（读 /proc，约 1 秒）")
                        .font(.system(size: 11))
                        .foregroundStyle(Yx.dim)
                }
                .frame(minHeight: 22)
            }
            if let healthNote {
                Text(healthNote)
                    .font(.system(size: 12))
                    .foregroundStyle(Yx.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 12)
    }

    /// 归堆后的一类可收拾的东西。
    /// ⚠️ 用 struct 不用元组：`ForEach` 的 `id:` 是个 key path，而 **key path 进不了元组**。
    private struct JunkGroup: Identifiable {
        let what: String
        let items: [Health.Junk]
        var id: String { what }
    }

    /// 按类别归堆：一堆 Gradle 守护进程列成十行没意义，
    /// 而「Gradle 编译守护进程 · 3 个 · 3100 MB」一眼就够做决定。占得多的排前面。
    private var junkGroups: [JunkGroup] {
        Dictionary(grouping: junk ?? [], by: { $0.what })
            .map { JunkGroup(what: $0.key, items: $0.value) }
            .sorted { totalMb($0.items) > totalMb($1.items) }
    }

    /// 「挑要收拾的」。
    ///
    /// ⚠️ **杀之前把要杀的逐条摆出来。** 一键收拾要是能弄丢东西，
    /// 它就不是「方便」而是陷阱 —— 所以先看清、再点。
    /// ⚠️ **逐类可选，而且默认一个都不勾**：只有一类时「全杀」还行，
    /// 扫出好几类就太粗（可能只想收 Gradle 缓存、留着正在跑的搜索）。
    /// 让人主动勾，比让人记得取消安全。
    @ViewBuilder
    private var fixSheet: some View {
        let groups = junkGroups
        let chosen = groups.filter { picked.contains($0.what) }.flatMap(\.items)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("挑要收拾的")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Yx.onSurface)
                YxHint("缓存和跑飞的进程收掉不丢东西。会话那一类会真的关掉 —— 但对话存档留着，之后还能接回来。")

                ForEach(groups) { g in
                    let on = picked.contains(g.what)
                    // 最久的那个跑了多久 —— 判断「是不是跑飞了」看这个。同样先算好再进 `Text`
                    // ⚠️ 会话那类 ageSec 是「多久没动过」不是「跑了多久」，
                    // 照进程的话术写会把「闲了 17 天」说成「跑了 17 天」，正好反了
                    let line = g.what == "idle"
                        ? "\(g.items.count) 个 · \(totalMb(g.items)) MB · 最久 \(oldestDays(g.items)) 天没动过"
                        : "\(g.items.count) 个 · \(totalMb(g.items)) MB · 最久跑了 \(oldestMin(g.items)) 分钟"
                    Button {
                        if on { picked.remove(g.what) } else { picked.insert(g.what) }
                    } label: {
                        HStack(spacing: 10) {
                            Text(on ? "✓" : "○")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(on ? Yx.onCopperBox : Yx.muted)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(junkText(g.what))
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle(on ? Yx.onCopperBox : Yx.onSurface)
                                Text(line)
                                    .font(.mono(11))
                                    .foregroundStyle(Yx.dim)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(on ? Yx.copperBox : Yx.high, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                // ⚠️ steal 高的时候要**明说这个按钮救不了它**，别让人白按一次再失望
                if let v = health?.vitals, v.steal >= 10 {
                    Text("⚠️ 这台机器有 \(v.steal)% 的 CPU 被宿主机抢走了 —— 那个收拾不掉，得让服务商迁移实例。")
                        .font(.system(size: 12))
                        .foregroundStyle(Yx.error)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Button {
                    fixOpen = false
                    Task { await fix(chosen) }
                } label: {
                    // ⚠️ 一个都没勾就**禁用**，别让人点了个没反应的按钮
                    Text(chosen.isEmpty ? "先勾几个" : "收拾 \(chosen.count) 个 · \(totalMb(chosen)) MB")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(chosen.isEmpty ? Yx.dim : Yx.onCopper)
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(chosen.isEmpty ? Yx.high : Yx.copper, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(chosen.isEmpty)

                Button("算了") { fixOpen = false }
                    .font(.system(size: 14))
                    .foregroundStyle(Yx.muted)
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, Yx.pad).padding(.top, 20).padding(.bottom, 28)
        }
        .background(Yx.low)
        .presentationDetents([.medium, .large])
    }

    /// 体检：连一次、取一份读数、顺手扫一遍能收的，然后断开。
    private func loadHealth() async {
        healthBusy = true
        healthNote = nil
        defer { healthBusy = false }

        let (opened, note) = await openSession()
        guard let session = opened else { healthNote = note; return }
        defer { Task { await session.disconnect() } }
        await refreshHealth(on: session)
    }

    /// 在一条**已经连上**的连接上重新体检。收拾完也走这条 —— 分数要当场动给用户看。
    private func refreshHealth(on session: SSHSession) async {
        do {
            let out = try await session.exec(Health.command).stdout
            // ⚠️ `parse` 返回 nil 就说读不出来，**绝不猜一个数摆上去**：
            // 用户会照着它做决定（「看着还好，那就不是服务器的问题」）。
            guard let v = Health.parse(out) else {
                healthNote = "读不出这台机器的状态（不是 Linux？）"
                return
            }
            health = Health.score(v)
            healthNote = nil
            // 顺手扫一遍「有什么可以安全收掉的」——**只看不收**。
            // 扫失败就当没扫到，不为它报错：体检本身已经出来了，那才是主菜。
            if let scan = try? await session.exec(Health.scanCommand).stdout {
                junk = Health.junk(from: scan)
            }
        } catch is CancellationError {
            // ⚠️ 取消不是失败 —— 别把它写成一句钉在界面上的假错误（#78 / #79）
        } catch {
            healthNote = Explain.connection(error, target: session.target, hostname: h.hostname)
        }
    }

    /// 收拾选中的那几类。
    ///
    /// ⚠️ **pid 只从 `Health.junk(from:)` 来**（`killCommand` 那边也再挡一道），
    /// 这里不拼任何命令 —— 免得哪天改 UI 时留下一个能杀任意进程的口子。
    /// ⚠️ 跑完**立刻重新体检一次**：分数当场变，用户才看得出这一下有没有用。
    private func fix(_ chosen: [Health.Junk]) async {
        guard let cmd = Health.killCommand(chosen) else { return }
        fixing = true
        defer { fixing = false }

        let (opened, note) = await openSession()
        guard let session = opened else { healthNote = note; return }
        defer { Task { await session.disconnect() } }
        // 杀不动就杀不动（进程可能自己先退了），照样重新体检一遍看现在什么样
        _ = try? await session.exec(cmd)
        await refreshHealth(on: session)
        picked = []
    }

    // MARK: - 体检的话术
    //
    // ⚠️ 都拼在这一层，不放进 [Health]：那边是纯逻辑（要能单测、拿不到界面），
    // 只吐代号和数字。这几个 switch 就是把代号翻成人话的地方。

    /// ≥85 绿、≥65 黄、≥40 橙，再低就是红。
    /// ⚠️ 黄用 `amber` 是**符合**它「需要你动手」那层语义的：分数掉到这一档，
    /// 下面正好摆着「可以收拾」那个按钮。不是拿它当装饰色。
    private func scoreTone(_ score: Int) -> Color {
        if score >= 85 { return Yx.teal }
        if score >= 65 { return Yx.amber }
        if score >= 40 { return Yx.copper }
        return Yx.error
    }

    private func verdictText(_ score: Int) -> String {
        switch Health.verdict(score) {
        case "easy":     return "松快"
        case "tight":    return "有点紧"
        case "strained": return "很吃力"
        default:         return "快扛不住了"
        }
    }

    private func vitalsLine(_ v: Health.Vitals) -> String {
        "负载 \(oneDecimal(v.load1))/\(v.cores)核 · 内存 \(v.memUsedPct)% · 交换 \(v.swapUsedPct)% · 被抢 \(v.steal)% · 盘 \(v.diskUsedPct)%"
    }

    private func issueText(_ i: Health.Issue) -> String {
        switch i.code {
        case "steal":
            switch i.level {
            case 2:  return "CPU 被宿主机抢走 \(i.value)% —— 这台云主机所在的物理机严重超卖"
            case 1:  return "CPU 被宿主机抢走 \(i.value)%"
            default: return "CPU 被宿主机抢走 \(i.value)%，偏高"
            }
        case "swap":
            switch i.level {
            case 2:  return "交换分区用掉 \(i.value)% —— 机器在颠簸，什么都会变慢"
            case 1:  return "交换分区用掉 \(i.value)%"
            default: return "开始用交换分区了（\(i.value)%）"
            }
        case "load":
            // ⚠️ load 这条的 `value` 是**负载倍数 ×10**（`Issue.value` 是 Int，存不了小数）
            let x = oneDecimal(Double(i.value) / 10)
            return i.level >= 1 ? "负载是核数的 \(x) 倍，进程在排长队" : "负载是核数的 \(x) 倍"
        case "mem":
            return i.level >= 1 ? "内存用掉 \(i.value)%，快没了" : "内存用掉 \(i.value)%"
        case "disk":
            return i.level >= 1 ? "根分区用掉 \(i.value)%，快写不进去了" : "根分区用掉 \(i.value)%"
        default:
            return ""
        }
    }

    private func junkText(_ code: String) -> String {
        switch code {
        case "gradle": return "Gradle 编译守护进程"
        case "kotlin": return "Kotlin 编译守护进程"
        case "rg":     return "跑飞的 rg 全盘搜索"
        case "hog":    return "一直霸着 CPU 的进程"
        case "idle":   return "很久没动过的会话"
        default:       return code
        }
    }

    private func oldestDays(_ items: [Health.Junk]) -> Int64 {
        (items.map(\.ageSec).max() ?? 0) / 86400
    }

    private func totalMb(_ items: [Health.Junk]) -> Int64 {
        items.reduce(0) { $0 + $1.rssKb } / 1024
    }

    private func oldestMin(_ items: [Health.Junk]) -> Int64 {
        (items.map(\.ageSec).max() ?? 0) / 60
    }

    /// 一位小数。⚠️ 不用 `String(format:)` —— `Double` 自己的字面量形式就够
    /// （`89/10` 打出来就是 `8.9`），少绕一个 Foundation 的格式化。
    private func oneDecimal(_ x: Double) -> String { "\((x * 10).rounded() / 10)" }
}
