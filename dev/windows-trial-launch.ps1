# 试用包辅助启动：以交付目录内独立 profile 打开试用版 Yxi.exe，不碰机器上已有的配置/单实例锁。
# ⚠️ 只改启动那一刻的进程环境（启动后立即还原父进程环境）；不写全局/用户环境变量，
#    不动已安装的 Yxi（Program Files 路径直接拒绝），不传任何测试/自动更新参数，不改应用、不重建 jar。
# 用法（pwsh 6+ 推荐，Windows PowerShell 5.1 也可）：
#   pwsh -File dev\windows-trial-launch.ps1 -ExePath <交付目录>\Yxi\Yxi.exe
#   可选 -ProfileName <名>（默认 trial-profile；profile 建在交付目录内，删目录即完全清理）
# 使用说明与本次试用包的事实清单见 dev/windows-trial-launch.md
param(
    [Parameter(Mandatory=$true)][string]$ExePath,
    [string]$ProfileName = 'trial-profile'
)
$ErrorActionPreference = 'Stop'
$exe = (Resolve-Path -LiteralPath $ExePath).Path
if ((Split-Path $exe -Leaf) -ne 'Yxi.exe') { throw "ExePath 应指到交付目录里的 Yxi.exe：$exe" }
if ($ProfileName -notmatch '^[\p{L}\p{N}_-][\p{L}\p{N}_.-]{0,63}$') { throw 'ProfileName 必须是以字母、数字、下划线或短横线开头的目录名，最多 64 字符。' }
# 交付目录 = Yxi 应用目录的上级（app-image 布局：<交付目录>\Yxi\Yxi.exe）
$deliverRoot = Split-Path (Split-Path $exe -Parent) -Parent
if ($deliverRoot -like ($env:ProgramFiles + '*')) { throw "这像已安装位置（$deliverRoot 在 Program Files 下）；本脚本只用于交付目录试用包，不碰已安装的 Yxi" }
$trialHome = Join-Path $deliverRoot $ProfileName
New-Item -ItemType Directory -Path (Join-Path $trialHome 'Roaming'),(Join-Path $trialHome 'Local') -Force | Out-Null

# 四项用户态入口全指向包内 profile：HOME/APPDATA/LOCALAPPDATA（进程环境）+ user.home（JAVA_TOOL_OPTIONS）；
# 应用配置与单实例锁都落在 profile 里，与机器上已有安装互不相见。Start-Process 非等待：
# 子进程在创建瞬间继承这套环境，finally 随即还原父进程，变量不外溢、不写注册表/用户环境。
$prevJTO = $env:JAVA_TOOL_OPTIONS
$prevHome = $env:HOME
$prevAppData = $env:APPDATA
$prevLocalAppData = $env:LOCALAPPDATA
try {
    $env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $trialHome + '"'
    $env:HOME = $trialHome
    $env:APPDATA = Join-Path $trialHome 'Roaming'
    $env:LOCALAPPDATA = Join-Path $trialHome 'Local'
    Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe -Parent)
    Write-Output "已启动试用包（独立 profile：$trialHome）；父进程环境已还原。关闭应用后删 $trialHome 即完全清理。"
} finally {
    $env:JAVA_TOOL_OPTIONS = $prevJTO
    $env:HOME = $prevHome
    $env:APPDATA = $prevAppData
    $env:LOCALAPPDATA = $prevLocalAppData
}
