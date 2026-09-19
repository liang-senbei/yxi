# Windows 本机打包：Windows uber jar → jpackage app-image →（可选）Velopack Setup.exe。
# 与 CI（.github/workflows/desktop.yml）同参数的本地版；CI 跑不了/想本机快速出包时用。
# ⚠️ 只在本脚本自己的 OutputDir 里产出，不安装、不发布、不碰已装的 Yxi 和任何生产配置。
# 用法（pwsh 6+ 推荐，Windows PowerShell 5.1 也可）：
#   pwsh -File dev/windows-package.ps1 -JarPath <Yxi-windows-x64-*.jar> -OutputDir <新的空目录> [-VpkDllPath <vpk.dll>] [-RunSmoke] [-SkipVpk]
# 输入清单：
#   1) Windows uber jar（必须 -Pyxi.os=win 打出的那种，文件名 Yxi-windows-x64-<版本>.jar；
#      名字来自 android/desktop/build.gradle.kts 的 packageVersion，脚本从这里取版本，不另设参数。
#      例：be45eb3 构建的 Yxi-windows-x64-1.2.0.jar，SHA256 75255f5f4503eaa26bb0258bd9c11eccdf4a070cb8252ec2a4166aa78282a82f）
#   2) 带 jpackage 的 JDK21+（缺省找 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'，同 windows-native-verify.ps1）
#   3) icon.ico（缺省取仓库 android/desktop/icon.ico）
#   4) -SkipVpk 不打 Setup 时可省：打 Setup 要 .NET（二选一）：
#      a) 有 SDK：vpk 1.2.0 自动装进 <OutputDir>\tools，不碰全局工具，卸载 = 删掉该目录
#      b) 只有运行库没有 SDK：传 -VpkDllPath 指到官方 vpk 1.2.0 nupkg 解包的
#         tools/net8.0/any/vpk.dll，用现有 dotnet 直接跑该 dll，不安装任何工具
#   5) -RunSmoke 才跑 3 秒冒烟（默认不跑）；冒烟的 HOME/APPDATA/LOCALAPPDATA/user.home
#      全部隔离到 <OutputDir>\smoke-profile，跑完还原环境变量，不读写真实用户状态
# 产物：<OutputDir>\Yxi\（app-image）、<OutputDir>\Releases\（Setup.exe / nupkg / releases.win.json）、各 SHA256 清单
param(
    [Parameter(Mandatory=$true)][string]$JarPath,
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [string]$JdkPath = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot',
    [string]$IconPath = '',
    [string]$VpkDllPath = '',
    [switch]$RunSmoke,
    [switch]$SkipVpk
)
$ErrorActionPreference = 'Stop'
$jar = (Resolve-Path -LiteralPath $JarPath).Path
if (Test-Path -LiteralPath $OutputDir) { throw 'Use a new output directory; existing builds are preserved.' }
$out = [IO.Path]::GetFullPath($OutputDir)
if (-not $IconPath) { $IconPath = Join-Path $PSScriptRoot '..\android\desktop\icon.ico' }
$icon = (Resolve-Path -LiteralPath $IconPath).Path
$jpackage = Join-Path $JdkPath 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw "jpackage 不在 $jpackage —— JdkPath 指到带 jpackage 的 JDK21+" }

# 版本只认 jar 文件名（= build.gradle.kts packageVersion），顺带挡住把 Linux jar 拖上 Windows 的手误
if ($jar -notmatch 'Yxi-windows-x64-(?<v>.+)\.jar$') { throw "文件名不是 Yxi-windows-x64-<版本>.jar：$jar（Windows 包要 -Pyxi.os=win 打）" }
$v = $Matches.v
# 包里真有 Windows 的 Skia 原生库才继续（jar 巨大，先查这个最便宜的错）。
# 位置实测在 uber jar 根（Compose 打包把 skiko 原生库提到根目录，带 .sha256 伴生文件），
# 不在 org/jetbrains/skiko/ 下——按实际条目匹配，别"修"回包路径。
$skiko = & (Join-Path $JdkPath 'bin/jar.exe') tf $jar | Select-String -Pattern '^skiko-windows-x64\.dll$'
if (-not $skiko) { throw "$jar 里没有 skiko-windows-x64.dll —— 这是 Linux/mac 的 jar，重打：./gradlew -Pyxi.os=win :desktop:packageUberJarForCurrentOS" }

New-Item -ItemType Directory -Path $out | Out-Null
$inputDir = Join-Path $out 'input'
New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item -LiteralPath $jar -Destination $inputDir
$sha = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
"jar $([IO.Path]::GetFileName($jar)) SHA256 $sha" | Out-File (Join-Path $out 'artifact-sha256.txt')

# jpackage app-image：--add-modules ALL-MODULE-PATH 同 windows-native-verify.ps1（精简 runtime 曾缺 java.net.http，别减）。
# -Djpackage.app-version 显式注入：CI（和下面的 cfg 交叉校验、vpk --packVersion）都从 cfg 的这行取版本，
# 不依赖 jpackage 对 --app-version 的任何隐式落盘行为。
& $jpackage --type app-image --name Yxi --input $inputDir --main-jar ([IO.Path]::GetFileName($jar)) `
    --main-class app.yxi.desktop.MainKt --app-version $v --add-modules ALL-MODULE-PATH `
    --java-options "-Djpackage.app-version=$v" `
    --icon $icon --dest $out
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
$appImage = Join-Path $out 'Yxi'
$exe = Join-Path $appImage 'Yxi.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "装完没找到 $exe" }
# 版本写进了 cfg 才算数（CI 从同一行读版本，两边必须对上）
$cfg = @((Join-Path $appImage 'app\Yxi.cfg'), (Join-Path $appImage 'lib\app\Yxi.cfg')) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$cfgV = (Select-String -LiteralPath $cfg -Pattern '^java-options=-Djpackage.app-version=(.+)$').Matches.Groups[1].Value.Trim()
if ($cfgV -ne $v) { throw "Yxi.cfg 版本 $cfgV 与 jar 文件名版本 $v 不一致" }

# -RunSmoke：显式可选的 3 秒开窗退出冒烟（打印 smoke ok）。
# 四个用户态入口全隔离到输出目录私有 profile：HOME、APPDATA、LOCALAPPDATA（进程环境）+
# user.home（JAVA_TOOL_OPTIONS 传入），单实例锁与应用配置都落在同一目录，真实用户状态零接触；
# finally 里逐项还原，不留环境残留。
if ($RunSmoke) {
    $smokeHome = Join-Path $out 'smoke-profile'
    New-Item -ItemType Directory -Path "$smokeHome\Roaming","$smokeHome\Local" | Out-Null
    $prevJTO = $env:JAVA_TOOL_OPTIONS
    $prevHome = $env:HOME
    $prevAppData = $env:APPDATA
    $prevLocalAppData = $env:LOCALAPPDATA
    try {
        $env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $smokeHome + '"'
        $env:HOME = $smokeHome
        $env:APPDATA = "$smokeHome\Roaming"
        $env:LOCALAPPDATA = "$smokeHome\Local"
        $p = Start-Process -FilePath $exe -ArgumentList '--smoke' -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $out 'smoke-out.txt') -RedirectStandardError (Join-Path $out 'smoke-err.txt')
        if (-not $p.WaitForExit(120000)) { & taskkill /PID $p.Id /T /F; throw 'smoke 超时（120s），已杀进程树' }
        if ($p.ExitCode -ne 0) { throw "smoke 退出码 $($p.ExitCode)" }
        if (-not (Select-String -Path (Join-Path $out 'smoke-out.txt'),(Join-Path $out 'smoke-err.txt') -Pattern 'smoke ok' -Quiet)) { throw '缺 smoke ok' }
        Write-Output 'smoke passed'
    } finally {
        $env:JAVA_TOOL_OPTIONS = $prevJTO
        $env:HOME = $prevHome
        $env:APPDATA = $prevAppData
        $env:LOCALAPPDATA = $prevLocalAppData
    }
}

# 更完整的六项原生验证（host/credential/browser）继续走 dev/windows-native-verify.ps1，这里不重复。
if (-not $SkipVpk) {
    # 两台机器两种打法，pack 参数完全一致：
    #   传了 -VpkDllPath：只有 .NET 运行库没有 SDK 的机器——用现有 dotnet 直接跑官方 nupkg 解包的 vpk.dll，不安装任何工具
    #   没传：有 SDK——vpk 私有装进输出目录 tools（--tool-path），绝不 -g 全局安装；卸载 = 删 <OutputDir>\tools
    $packArgs = @('pack','--packId','Yxi','--packVersion',$cfgV,'--packDir',$appImage,'--mainExe','Yxi.exe',
        '--packTitle','Yxi','--packAuthors','Yxi','--icon',$icon,'--noPortable','--skipVeloAppCheck',
        '--outputDir',(Join-Path $out 'Releases'))
    if ($VpkDllPath) {
        $vpkDll = (Resolve-Path -LiteralPath $VpkDllPath).Path
        & dotnet $vpkDll @packArgs
    } else {
        $toolsDir = Join-Path $out 'tools'
        dotnet tool install --tool-path $toolsDir vpk --version 1.2.0
        if ($LASTEXITCODE -ne 0) { throw 'vpk 安装失败（需要 .NET SDK）；无 SDK 的机器改传 -VpkDllPath，只到 app-image 可加 -SkipVpk 重跑' }
        & (Join-Path $toolsDir 'vpk.exe') @packArgs
    }
    if ($LASTEXITCODE -ne 0) { throw 'vpk pack failed' }
    Get-ChildItem (Join-Path $out 'Releases') | ForEach-Object { Write-Output ("Releases/ " + $_.Name + " " + $_.Length) }
}
Write-Output "app-image: $appImage"
Write-Output "jar SHA256: $sha"
