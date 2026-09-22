$ErrorActionPreference = 'Stop'
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../android/desktop/src/main/resources/app/yxi/desktop/link-local.ps1'))
. $source -Mode library
function Check([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Fixture-Key([byte]$Value) {
    [byte[]]$blob = @(0,0,0,11) + [Text.Encoding]::ASCII.GetBytes('ssh-ed25519') + @(0,0,0,32) + (@($Value) * 32)
    'ssh-ed25519 ' + [Convert]::ToBase64String($blob)
}
$base = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$root = Join-Path $base ('yxi-key-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$workers = @()
try {
    $keyDirectory = Join-Path $root 'user directory'
    New-Item -ItemType Directory -Path $keyDirectory | Out-Null
    $path = Join-Path $keyDirectory 'authorized_keys'
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $principal = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($principal, 'FullControl', 'Allow')))
    $old = "# existing configuration`r`nssh-rsa AAAA existing-key`r`n"
    [IO.File]::WriteAllText($path, $old)
    $a = Fixture-Key 1
    $b = Fixture-Key 2
    $c = Fixture-Key 3
    $firstPath = Join-Path $keyDirectory 'first_keys'
    Write-YxiAuthorizedKey $firstPath $a $acl
    Check ([IO.File]::ReadAllText($firstPath).Contains($a)) 'First key publication failed'
    Check ((Get-Acl -LiteralPath $firstPath).AreAccessRulesProtected) 'First file inherits unrelated permissions'
    Write-YxiAuthorizedKey $path $a $acl
    Check ([IO.File]::ReadAllText($path).StartsWith($old)) 'Existing keys changed'
    Check ([IO.File]::ReadAllText($path + '.yxi.bak') -eq $old) 'Backup does not preserve original content'
    $once = [IO.File]::ReadAllText($path)
    Write-YxiAuthorizedKey $path $a $acl
    Check ([IO.File]::ReadAllText($path) -eq $once) 'Repeated key was appended twice'
    Check ([IO.File]::ReadAllText($path + '.yxi.bak') -eq $old) 'No-op changed the backup'
    $rejected = $false
    try { Write-YxiAuthorizedKey $path 'ssh-ed25519 YQ==' $acl } catch { $rejected = $true }
    Check $rejected 'Malformed public key accepted'
    Check ([IO.File]::ReadAllText($path) -eq $once) 'Malformed input changed the key file'
    foreach ($key in @($b, $c)) {
        $worker = @'
$ErrorActionPreference = 'Stop'
. SOURCE -Mode library
$acl = New-Object Security.AccessControl.FileSecurity
$acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl', 'Allow')))
Write-YxiAuthorizedKey TARGET PUBLICKEY $acl
'@
        $worker = $worker.Replace('SOURCE', "'" + $source.Replace("'", "''") + "'").Replace('TARGET', "'" + $path.Replace("'", "''") + "'").Replace('PUBLICKEY', "'$key'")
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($worker))
        $workers += Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList "-NoProfile -NonInteractive -EncodedCommand $encoded"
    }
    foreach ($worker in $workers) { Check ($worker.WaitForExit(20000)) 'Concurrent update timed out'; Check ($worker.ExitCode -eq 0) 'Concurrent update failed' }
    $merged = [IO.File]::ReadAllText($path)
    foreach ($key in @($a, $b, $c)) { Check (@(($merged -split '\r?\n') | Where-Object { $_.StartsWith($key + ' ') }).Count -eq 1) 'Concurrent update lost or duplicated a key' }
    Check ((Get-Acl -LiteralPath $path).AreAccessRulesProtected) 'Published file inherits unrelated permissions'
    # Force atomic replacement to fail; the active file must survive intact.
    [IO.File]::Delete($path + '.yxi.bak')
    New-Item -ItemType Directory -Path ($path + '.yxi.bak') | Out-Null
    $failed = $false
    try { Write-YxiAuthorizedKey $path (Fixture-Key 4) $acl } catch { $failed = $true }
    Check $failed 'Expected replacement failure was not surfaced'
    Check ([IO.File]::ReadAllText($path) -eq $merged) 'Failed replacement changed active keys'
    Check (@(Get-ChildItem -LiteralPath $keyDirectory -Filter '*.tmp').Count -eq 0) 'Temporary key file was not cleaned up'
    Write-Output 'PASS: preserve, backup, deduplicate, reject malformed keys, concurrent merge, protected ACL, failed replacement'
} finally {
    foreach ($worker in $workers) { if (-not $worker.HasExited) { $worker.Kill() } }
    $resolved = [IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith($base + '\yxi-key-test-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected cleanup directory' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
