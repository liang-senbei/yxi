"""推之前的文本体检。每一条都对应一次真的 CI 失败。

⚠️ 注释和字符串一律先剥掉再查 —— 否则「写了一条警告注释」本身就会被报成问题
（第一版就是这么自己咬自己的）。
"""
import pathlib, re, sys

def strip(src: str) -> str:
    """去掉注释和字符串字面量，只留代码骨架。"""
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)          # 多行字符串
    src = re.sub(r'#"(?:[^"]|"(?!#))*"#', '""', src)      # raw string
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)       # 普通字符串
    src = re.sub(r'/\*(?:.|\n)*?\*/', '', src)            # 块注释
    src = re.sub(r'//[^\n]*', '', src)                    # 行注释
    return src

CHECKS = [
    # (正则, 说明) —— 在**剥掉注释和字符串**之后的代码上跑
    (re.compile(r'\.(string|array|int|int64|bool)\s*\?\?'),
     "YxiKit.JSON 的 .string/.array/.int/.bool 是**非可选**的（回落成 \"\"/[]/0），别写 ??"),
    (re.compile(r'^\s*(?:private |fileprivate |public )?(?:enum|struct|class) '
                r'(?:State|Environment|Binding|Published)\b', re.M),
     "这些名字会遮住 SwiftUI 的属性包装器（报 'State' cannot be used as an attribute），换名"),
]

# raw string 里的 \u{..}
#
# ⚠️ **只对 ICU 那条路报警**：`NSRegularExpression(pattern:)` 和
# `replacingOccurrences(options: .regularExpression)` 走的是 ICU，raw string 里的
# `\u{1B}` 会原样交给它，被按 ICU 自己的语法解释（#136 就是这么把模型名吃光的）。
# 而 Swift 自己的 `Regex(#"…"#)` 是**另一个引擎**，`\u{00C0}` 它认得 ——
# 已实测 `Sautéing…` 两种写法都匹配。不分开的话这条规则会误报正确代码。
RAW_ESC = re.compile(r'#"[^"\n]*\\u\{')
ICU_CALL = re.compile(r'NSRegularExpression|replacingOccurrences')

bad = []
for f in sorted(pathlib.Path("Sources").rglob("*.swift")):
    raw = f.read_text()
    code = strip(raw)

    d = code.count("{") - code.count("}")
    if d:
        bad.append(f"{f} 花括号差 {d}（改嵌套的 SwiftUI 结构最容易漏收尾）")

    # ⚠️ 判「有没有 import」要看**行首的真 import 语句**。全文找字符串会被
    # 注释里提到的那句骗过去 —— 这个检查第一版就是这么放行了一个真错的
    # （SessionsScreen 的注释里正好写着 import WidgetKit）。
    if "WidgetCenter" in code and not re.search(r'^\s*import WidgetKit\b', raw, re.M):
        bad.append(f"{f} 用了 WidgetCenter 却没 import WidgetKit（要用 #if canImport 包着）")
    # ⚠️ **这里不能加「link.service 写错了」这类规则。** 试过：`SessionsScreen`
    # 里的 `link` 是 `Link`（写 `link.service` 是对的），`Watcher` 里的是 `HostLink`
    # （得写 `link.link.service`）—— **光看文本分不出来**，规则一加就误报正确代码。
    # 这个文件只能放**不需要类型信息**就能判的检查；要类型的交给 CI。

    for rx, why in CHECKS:
        for m in rx.finditer(code):
            line = code[:m.start()].count("\n") + 1
            bad.append(f"{f}:{line} {why}")

    for i, line in enumerate(raw.split("\n"), 1):
        s = line.strip()
        if s.startswith("//") or s.startswith("///") or s.startswith("*"):
            continue
        # 同一行、或紧邻的上一行里出现 ICU 那两个调用才算数
        near = line + (raw.split("\n")[i - 2] if i >= 2 else "")
        if RAW_ESC.search(line) and ICU_CALL.search(near):
            bad.append(f"{f}:{i} raw string 里的 \\u{{..}} 会原样交给 ICU（NSRegularExpression / "
                       "replacingOccurrences），按它自己的语法解释会吃掉整段。"
                       "改成普通字符串让 Swift 先转义。（Swift 的 Regex 不受影响）")

for b in bad:
    print("❌", b)
if not bad:
    print("✅ precheck 通过（只是文本检查，真编译还得看 CI）")
sys.exit(1 if bad else 0)
