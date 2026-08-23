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
                                onOpen: { onOpen(h) },
                                onEdit: { editing = h },
                                onWatch: { onSetWatch(h.id, !h.watch) }
                            )
                        }
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
    let onOpen: () -> Void
    let onEdit: () -> Void
    let onWatch: () -> Void

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
                    .onTapGesture(perform: onOpen)

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
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
        }
        .contextMenu {
            Button("连接", action: onOpen)
            Button("改主机", action: onEdit)
        }
    }
}
