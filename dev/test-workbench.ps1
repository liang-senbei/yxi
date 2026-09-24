param([string]$ToolchainPath = '')
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskClasses = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'workbench-test-classes.txt') | Where-Object { $_.Trim() })
if ($taskClasses.Count -eq 0 -or ($taskClasses | Select-Object -Unique).Count -ne $taskClasses.Count) { throw 'Invalid workbench test selection' }
$taskArguments = @('-Pyxi.desktopOnly=true', ':desktop:test', '--no-daemon', '--max-workers=1', '--console=plain', '--rerun-tasks')
if ($ToolchainPath) { $taskArguments += "-Porg.gradle.java.installations.paths=$ToolchainPath" }
foreach ($taskClass in $taskClasses) {
    if ($taskClass -notmatch '^app\.yxi\.(desktop|agent)\.[A-Za-z0-9]+Test$') { throw 'Only explicit workbench unit classes are allowed' }
    $taskArguments += @('--tests', $taskClass)
}
Push-Location (Join-Path $taskRepo 'android')
try {
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Workbench regression failed' }
} finally { Pop-Location }
. (Join-Path $PSScriptRoot 'workbench-report-check.ps1')
$taskTotal = Test-WorkbenchReports -ReportDirectory (Join-Path $taskRepo 'android/desktop/build/test-results/test') -Classes $taskClasses
Write-Output "Workbench regression verified: $($taskClasses.Count) classes, $taskTotal tests, no failures/errors/skips"

