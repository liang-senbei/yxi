import SwiftUI
import YxiKit
import UIKit   // UIPasteboard：公钥要能一键复制

/// 加 / 改主机：任意 IP、**任意端口**、用户名、密码或密钥 —— 四样都不能写死。
///
/// ⚠️ 两个细节不能省（TROUBLESHOOTING #68）：
///   · **改了地址或端口，存的主机指纹必须作废**（`hostKey = nil`）
///   · **删除要两下** —— 主机记录里存着密码密文，手机上误触一下就没了
@MainActor
struct HostEditor: View {

    /// nil = 新建
    let editing: Host?
    let canInstallKey: Bool
    let onSave: (Host) -> Void
    let onDelete: (String) -> Void
    let onInstallKey: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var alias = ""
    @State private var hostname = ""
    @State private var port = "22"
    @State private var username = "root"
    @State private var usePassword = false
    @State private var password = ""
    @State private var confirmDelete = false
    @State private var saveError: String?

    var body: some View {
        // ⚠️ **表要能滚。** 半开的表放不下这么多字段，「保存」落在屏幕外 ——
        // 用户得先把表往上拖才够得着，而没有任何东西提示他要拖。
        // 所以这里**不给 `.presentationDetents([.medium])`**，就用默认的整高卡片；
        // 键盘避让 SwiftUI 的 ScrollView 自己会做（安卓那边要手动 `imePadding`）。
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(editing != nil ? "改主机" : "加新主机")
                    .font(.system(size: 22, weight: .semibold))

                YxField(label: "名字（随便起，只给你自己看）", text: $alias)

                // ⚠️ 别名≠地址：手机上没有 ~/.ssh/config，「station」「天亮」这类
                // SSH 别名解析不了，这一栏必须是真地址。
                // 标签曾经写「主机名 / IP」，等于在邀请用户填别名（#27）。
                HStack(alignment: .bottom, spacing: 10) {
                    YxField(label: "IP 或域名，如 38.244.50.31", text: $hostname, mono: true)
                    YxField(label: "端口", text: $port, mono: true, number: true).frame(width: 96)
                }
                // ⚠️ **打字的时候只做「全角→半角」这种安全归一，绝不动结构。**
                // 试过「一次进来一大段就当粘贴、顺手拆出 :port」——不成立：
                // 输入法整词上屏会触发，打到 `…147:22` 时端口被切走、剩下的 `22` 落回地址栏。
                // **在用户手指底下改他正在打的东西，跟「列表在手指下重排」是同一类错误**（#59）。
                .onChange(of: hostname) { _, new in
                    let n = HostInput.normalize(new)
                    if n != new { hostname = n }
                }
                .onChange(of: port) { _, new in
                    let n = String(new.filter(\.isNumber).prefix(5))
                    if n != new { port = n }
                }

                // 拆分放到**保存**时做 —— 那时整串才完整。中间只**告知，不动他的输入**。
                if let preview = HostInput.splitPreview(hostname) {
                    Text(preview).font(.system(size: 11)).foregroundStyle(Yx.teal)
                }
                if let bad = HostInput.suspiciousCharacter(in: hostname) {
                    Text("地址里有个连不上的字符：\(bad) —— 多半是中文输入法打出来的，删掉重打")
                        .font(.system(size: 11))
                        .foregroundStyle(Yx.error)
                        .fixedSize(horizontal: false, vertical: true)
                }

                YxField(label: "用户名", text: $username, mono: true)

                HStack(spacing: 4) {
                    seg("密钥", selected: !usePassword) { usePassword = false }
                    seg("密码", selected: usePassword) { usePassword = true }
                }
                .padding(4)
                .background(Yx.container, in: Capsule())

                if usePassword {
                    YxField(
                        label: editing?.sealedPassword != nil ? "密码（留空 = 不改）" : "密码",
                        text: $password, secure: true
                    )
                    YxHint("密码用设备密钥加密后保存，不落明文。连上后可以一键装公钥，之后免密。")
                } else {
                    YxHint("用 App 自己的 ed25519 密钥。先去右上角「公钥」把它贴进目标机的 authorized_keys。")
                }

                if let e = saveError {
                    Text(e).font(.system(size: 11)).foregroundStyle(Yx.error)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button(action: save) {
                    Text("保存")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Yx.onCopper)
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(hostname.isEmpty ? Yx.high : Yx.copper, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(hostname.isEmpty)

                if let h = editing {
                    HStack(spacing: 10) {
                        if canInstallKey {
                            secondary("装公钥", action: onInstallKey)
                        }
                        // ⚠️ **删除要两下** —— 手机上误触一下就没了，而主机记录里有密码密文
                        secondary(confirmDelete ? "再点一次删除" : "删除", danger: confirmDelete) {
                            if !confirmDelete { confirmDelete = true; return }
                            onDelete(h.id)
                            dismiss()
                        }
                    }
                    YxHint("改地址或端口会作废已记住的指纹，下次连接重新确认一次。")
                }
            }
            .padding(.horizontal, Yx.pad).padding(.top, 20).padding(.bottom, 28)
        }
        .background(Yx.low)
        .foregroundStyle(Yx.onSurface)
        .onAppear {
            guard let h = editing else { return }
            alias = h.alias; hostname = h.hostname; port = String(h.port)
            username = h.username; usePassword = !h.useKey
        }
    }

    private func save() {
        // 保存时再拆一次 —— 手打的 `ip:2222` / `root@ip` 到这一刻才是完整的
        let parsed = HostInput.parse(hostname)
        let hn = parsed.host
        guard !hn.isEmpty else { return }
        // 地址栏里带的 `:port` / `root@` 优先于另外两栏 —— 用户刚敲进去的那一串才是他最新的意思
        let pt = parsed.port ?? Int(port) ?? 22
        let un = (parsed.user ?? username).trimmingCharacters(in: .whitespaces)
        let a = alias.trimmingCharacters(in: .whitespaces)
        // ⚠️ **改了地址或端口，存的主机指纹就必须作废。** 那把指纹属于旧机器；
        // 留着的话下次连新机器会报「指纹变了」—— 那是中间人警告的措辞，
        // 会把一次正常的改配置说成攻击（TROUBLESHOOTING #68）。
        let keepHostKey = editing.map { $0.hostname == hn && $0.port == pt } ?? false

        // 密码有三种去向：换新的 / 不动原来那份密文 / 选了密钥就清掉
        var sealed = editing?.sealedPassword
        if !usePassword {
            sealed = nil
        } else if !password.isEmpty {
            do { sealed = try Vault.seal(password) } catch {
                // ⚠️ 存不进钥匙串就**别假装存上了** —— 下次连接会拿着一份不存在的密码去认证，
                // 报出来的是「认证被拒」，跟密码打错长得一模一样，查起来能查半天
                saveError = "密码存不进钥匙串：\(error.localizedDescription)"
                return
            }
        }

        onSave(Host(
            id: editing?.id ?? UUID().uuidString,
            alias: a.isEmpty ? hn : a,
            hostname: hn,
            port: pt,
            username: un.isEmpty ? "root" : un,
            useKey: !usePassword,
            sealedPassword: sealed,
            hostKey: keepHostKey ? editing?.hostKey : nil,
            watch: editing?.watch ?? false
        ))
        dismiss()
    }

    private func seg(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(selected ? Yx.onCopper : Yx.dim)
                .frame(maxWidth: .infinity).frame(height: Yx.tap)
                .background(selected ? Yx.copper : Color.clear, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func secondary(_ label: String, danger: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(danger ? Yx.error : Yx.onSurface)
                .frame(maxWidth: .infinity).frame(height: Yx.tap)
                .background(Yx.container, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 公钥

/// 这台手机的公钥。贴进目标机的 `~/.ssh/authorized_keys`（一行）。
@MainActor
struct PublicKeySheet: View {
    let keys: KeyManager

    @State private var line = ""
    @State private var fingerprint = ""
    @State private var copied = false
    @State private var confirmRegen = false

    var body: some View {
        // ⚠️ 要能滚：矮屏上底部两个按钮会被挤出屏幕，够不着
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("这台手机的公钥").font(.system(size: 22, weight: .semibold))
                YxHint("点一下整块就复制。贴进目标机的 ~/.ssh/authorized_keys（一行）。撤销就删掉那一行，不用改 App 任何设置。")

                VStack(alignment: .leading, spacing: 8) {
                    Text(line)
                        .font(.mono(12))
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(copied ? "✓ 已复制到剪贴板" : "点这里复制")
                        .font(.system(size: 11))
                        .foregroundStyle(copied ? Yx.teal : Yx.dim)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Yx.lowest, in: RoundedRectangle(cornerRadius: Yx.blockRadius, style: .continuous))
                .contentShape(Rectangle())
                .onTapGesture(perform: copy)

                if !fingerprint.isEmpty {
                    Text("指纹 \(fingerprint)").font(.mono(11)).foregroundStyle(Yx.dim)
                }

                HStack(spacing: 10) {
                    Button(action: copy) {
                        Text(copied ? "已复制" : "复制公钥")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Yx.onCopper)
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(Yx.copper, in: Capsule())
                    }
                    Button { confirmRegen = true } label: {
                        Text("换一把")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Yx.onSurface)
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(Yx.container, in: Capsule())
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, Yx.pad).padding(.top, 20).padding(.bottom, 28)
        }
        .background(Yx.low)
        .foregroundStyle(Yx.onSurface)
        .onAppear(perform: load)
        // ⚠️ 换钥匙是**不可逆**的：旧私钥直接丢，所有装过旧公钥的服务器立刻连不上。
        // 所以必须先问一句，且把后果说清楚 —— 不是「确定吗」这种没信息量的提示。
        .alert("换一把新密钥？", isPresented: $confirmRegen) {
            Button("换", role: .destructive) {
                _ = try? keys.regenerate()
                copied = false
                load()
            }
            Button("算了", role: .cancel) {}
        } message: {
            Text("旧私钥会被丢掉，换不回来。\n\n所有已经装过旧公钥的服务器都会立刻连不上，要么重新装一次新公钥，要么手工删掉 authorized_keys 里那行 yxi。\n\n只有在怀疑私钥泄露、或想换台手机重来时才需要这么做。")
        }
    }

    private func load() {
        do {
            let id = try keys.identity()
            line = id.authorizedKeysLine
            fingerprint = id.fingerprint
        } catch {
            line = "生成失败：\(error.localizedDescription)"
            fingerprint = ""
        }
    }

    private func copy() {
        UIPasteboard.general.string = line
        copied = true
    }
}

// MARK: - 一键装公钥

/// 用密码连一次，把公钥追加进 `authorized_keys`，之后免密（等价 `ssh-copy-id`）。
///
/// 比 Moshi 的二维码配对流程还省事 —— 它得先在服务器上跑 `moshi-hook host setup`（PRD §2.4）。
@MainActor
struct InstallKeySheet: View {
    let host: Host
    let installer: any KeyInstalling
    let onSave: (Host) -> Void

    @State private var password = ""
    @State private var busy = false
    @State private var result: String?
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("给 \(host.alias) 装公钥").font(.system(size: 22, weight: .semibold))
                YxHint("用密码连一次，把这台手机的公钥追加进 ~/.ssh/authorized_keys，之后就免密了。相当于 ssh-copy-id。")
                YxField(label: "密码", text: $password, secure: true)
                if let r = result {
                    Text(r)
                        .font(.system(size: 13))
                        .foregroundStyle(failed ? Yx.error : Yx.teal)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    Task { await install() }
                } label: {
                    Text(busy ? "处理中…" : "连接并安装")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Yx.onCopper)
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(password.isEmpty || busy ? Yx.high : Yx.copper, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(password.isEmpty || busy)
            }
            .padding(.horizontal, Yx.pad).padding(.top, 20).padding(.bottom, 28)
        }
        .background(Yx.low)
        .foregroundStyle(Yx.onSurface)
    }

    private func install() async {
        busy = true; failed = false; result = "连接中…"
        do {
            let n = try await installer.installPublicKey(host: host, password: password)
            // 装成功了顺手切到密钥认证，并把这次用的密码存下来
            var updated = host
            updated.useKey = true
            updated.sealedPassword = try? Vault.seal(password)
            onSave(updated)
            result = "✅ 装好了（authorized_keys 里现有 \(n) 行 yxi 公钥），已切到密钥认证"
        } catch is CancellationError {
            // ⚠️ 取消不是失败 —— 别把它写成一句钉在界面上的假错误（#78/#79）
        } catch {
            failed = true
            result = error.localizedDescription
        }
        busy = false
    }
}

// MARK: - 小件

@MainActor
struct YxField: View {
    let label: String
    @Binding var text: String
    var mono = false
    var number = false
    var secure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.system(size: 11)).foregroundStyle(Yx.dim)
            Group {
                if secure {
                    SecureField("", text: $text)
                } else {
                    TextField("", text: $text)
                        .keyboardType(number ? .numberPad : .default)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .font(mono ? .mono(15) : .system(size: 15))
            .foregroundStyle(Yx.onSurface)
            .tint(Yx.copper)
            .padding(.horizontal, 14).frame(height: 48)
            .background(Yx.container, in: RoundedRectangle(cornerRadius: Yx.blockRadius, style: .continuous))
        }
    }
}
