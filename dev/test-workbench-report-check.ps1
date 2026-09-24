$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'workbench-report-check.ps1')
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('yxi-report-contract-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$class = 'app.yxi.desktop.FixtureTest'
$report = Join-Path $fixture "TEST-$class.xml"
$passed = 0
function Expect-Rejected([string]$Name, [string]$Xml) {
    if ($null -ne $Xml -and $Xml.Length -gt 0) { [IO.File]::WriteAllText($report, $Xml) }
    $rejected = $false
    try { $null = Test-WorkbenchReports -ReportDirectory $fixture -Classes @($class) } catch { $rejected = $true }
    if (-not $rejected) { throw "Invalid fixture accepted: $Name" }
    $script:passed++
}
Expect-Rejected 'missing report' ''
$valid = '<testsuite name="app.yxi.desktop.FixtureTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="fixture"/></testsuite>'
foreach ($kind in @('failures', 'errors', 'skipped')) { Expect-Rejected $kind ($valid.Replace("$kind=`"0`"", "$kind=`"1`"")) }
Expect-Rejected 'zero tests' ($valid.Replace('tests="1"', 'tests="0"'))
Expect-Rejected 'missing count' ($valid.Replace(' errors="0"', ''))
Expect-Rejected 'negative count' ($valid.Replace('tests="1"', 'tests="-1"'))
Expect-Rejected 'wrong class' ($valid.Replace('FixtureTest', 'DifferentTest'))
Expect-Rejected 'malformed XML' '<testsuite'
Expect-Rejected 'hidden skipped node' ($valid.Replace('<testcase name="fixture"/>', '<testcase name="fixture"><skipped/></testcase>'))
Expect-Rejected 'missing testcase' ($valid.Replace('<testcase name="fixture"/>', ''))
Expect-Rejected 'DTD' ('<!DOCTYPE testsuite [<!ENTITY fixture "1">]>' + $valid)
[IO.File]::WriteAllText($report, $valid)
$result = Test-WorkbenchReports -ReportDirectory $fixture -Classes @($class)
if ($result -ne 1) { throw 'Valid report did not pass' }
$passed++
# Delete only the exact two fixture paths created here, without recursive deletion.
Remove-Item -LiteralPath $report
Remove-Item -LiteralPath $fixture
Write-Output "Workbench report self-test passed: $passed cases"
