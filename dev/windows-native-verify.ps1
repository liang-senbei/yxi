param(
    [Parameter(Mandatory=$true)][string]$JarPath,
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [string]$JdkPath = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
)
$ErrorActionPreference = 'Stop'
$taskJar = (Resolve-Path -LiteralPath $JarPath).Path
if (Test-Path -LiteralPath $OutputDir) { throw 'Use a new output directory; existing builds are preserved.' }
$taskOutput = [IO.Path]::GetFullPath($OutputDir)
$taskInput = Join-Path $taskOutput 'input'
$taskProfile = Join-Path $taskOutput 'isolated-profile'
New-Item -ItemType Directory -Path $taskInput,$taskProfile | Out-Null
Copy-Item -LiteralPath $taskJar -Destination $taskInput
Get-FileHash -LiteralPath $taskJar -Algorithm SHA256 | Format-List | Out-File (Join-Path $taskOutput 'artifact-sha256.txt')
& (Join-Path $JdkPath 'bin/jpackage.exe') --type app-image --name YxiWorkbench --input $taskInput --main-jar ([IO.Path]::GetFileName($taskJar)) --main-class app.yxi.desktop.MainKt --dest $taskOutput --app-version 1.2.0 --add-modules ALL-MODULE-PATH
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
$taskExe = Join-Path $taskOutput 'YxiWorkbench/YxiWorkbench.exe'
$taskEnvironment = @{}
foreach ($taskName in @('APPDATA','LOCALAPPDATA','JAVA_TOOL_OPTIONS')) { $taskEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }
try {
    $env:APPDATA = Join-Path $taskProfile 'Roaming'
    $env:LOCALAPPDATA = Join-Path $taskProfile 'Local'
    New-Item -ItemType Directory -Path $env:APPDATA,$env:LOCALAPPDATA | Out-Null
    $taskImage = Join-Path $taskOutput 'browser-native.png'
    $taskCredentials = Join-Path $taskOutput 'synthetic-credentials'
    $env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $taskProfile + '" -Dyxi.browser.localFixture=true -Dyxi.browser.smokeImage="' + $taskImage + '" -Dyxi.credential.smokeDir="' + $taskCredentials + '"'
    $env:JAVA_TOOL_OPTIONS += ' -Dyxi.host.smokeRoot="' + $taskProfile + '"'
    foreach ($taskMode in @('host-smoke','host-reopen','smoke','credential-smoke','credential-reopen','browser-smoke')) {
        $taskStdout = Join-Path $taskOutput ($taskMode + '-out.txt')
        $taskStderr = Join-Path $taskOutput ($taskMode + '-err.txt')
        $taskArguments = if ($taskMode -eq 'credential-reopen') { '--credential-smoke --reopen' } elseif ($taskMode -eq 'host-reopen') { '--host-smoke --reopen' } else { '--' + $taskMode }
        $taskProcess = Start-Process -FilePath $taskExe -ArgumentList $taskArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr
        if (-not $taskProcess.WaitForExit(180000)) {
            # Only this test process tree, never the user's installed app.
            $taskProcess.Kill($true)
            throw "$taskMode timed out; logs kept at $taskOutput"
        }
        if ($taskProcess.ExitCode -ne 0) { throw "$taskMode failed with exit $($taskProcess.ExitCode); logs at $taskOutput" }
        $taskExpected = switch ($taskMode) {
            'host-smoke' { @('host Store migration and save ok') }
            'host-reopen' { @('host Store cross-process reopen ok') }
            'smoke' { @('smoke ok') }
            'credential-smoke' { @('credential native migration rotation and tamper checks ok') }
            'credential-reopen' { @('credential native cross-process reopen logout and login ok') }
            default { @('browser native render and live style ok','browser native pixels ok','browser native shutdown ok') }
        }
        $taskLog = Get-Content -LiteralPath $taskStdout -Raw
        foreach ($taskMarker in $taskExpected) { if (-not $taskLog.Contains($taskMarker)) { throw "Missing $taskMarker" } }
        Write-Output "$taskMode passed"
    }
    if (-not (Test-Path -LiteralPath $taskImage)) { throw 'Browser screenshot missing' }
    Write-Output "Native app-image verified: $taskExe"
} finally {
    foreach ($taskName in $taskEnvironment.Keys) { [Environment]::SetEnvironmentVariable($taskName, $taskEnvironment[$taskName], 'Process') }
}
