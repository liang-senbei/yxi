# R8 规则（2026-09-04 安全审计后开启混淆 + 资源压缩）
#
# ⚠️ **这个包里有三样东西开了混淆一定崩，因为它们不靠符号引用、靠名字找类**：
#   · jsch —— 算法实现是按**类名字符串**反射出来的（`com.jcraft.jsch.jce.AES128CTR` 这种），
#     混淆掉类名 = 连不上任何服务器，而且报错长得像网络问题，极难往混淆上想。
#   · BouncyCastle —— 同上，Provider 按名字装载。
#   · sherpa-onnx —— JNI 从原生层按名字回调 Java 类。
# 出事的表现分别是：SSH 认证失败 / 生成密钥失败 / 语音一按就崩。
# 改这个文件之后**必须在真机或模拟器上把这三条路各走一遍**，编译通过不算数。

# ── SSH：jsch（mwiede fork）
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ── 加密：BouncyCastle（jsch 生成 ed25519 密钥时用）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ── 终端模拟器：connectbot 的 termlib（libjni_cb_term.so）
#    ⚠️ **这一条是 E2E 抓出来的，不是想出来的**：它的 native 代码按**字段名**去取
#    `CellRun.fgRed` / `fgGreen` 这些整型字段。R8 一改名，`nativeInit` 就抛
#    `NoSuchFieldError` 并直接 abort 整个进程 —— 编译全绿、SSH 也正常，唯独一点「终端」就崩。
-keep class org.connectbot.terminal.** { *; }
-keepclassmembers class org.connectbot.terminal.** { <fields>; <methods>; }
-dontwarn org.connectbot.terminal.**

# ── androidx.graphics.path（也带 .so，保守 keep）
-keep class androidx.graphics.path.** { *; }
-dontwarn androidx.graphics.path.**

# ── 手机端语音识别：sherpa-onnx（JNI 双向调用）
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 枚举：我们把**枚举常量名当持久化的值**存过盘（模式、风格、语音引擎、档位），
#    名字被混淆 = 用户上次选的那些全部失效，而且是静默的（读不出来就回默认）。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# ── Compose / 协程 / AndroidX 自带 consumer rules，不用手写。
#    这里只补一条：R8 full mode 下反射拿泛型签名的地方需要保留签名。
-keepattributes Signature, InnerClasses, EnumConstantName

# ── 崩溃栈要能看：保留行号，但把源文件名抹成一个假的（别把 .kt 文件名送出去）
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ── HttpURLConnection 那个 PATCH 兜底会反射改私有字段 `method`（见 agent/Account.kt）。
#    它自己 runCatching 兜着，改不到就退回不了 PATCH —— 但那样会员改资料就废了，所以留着。
-keepclassmembers class java.net.HttpURLConnection { java.lang.String method; }
-dontwarn java.net.**
