param([ValidateSet('inspect','setup','library')][string]$Mode = 'inspect', [string]$RequestPath)
$ErrorActionPreference = 'Stop'
function Assert-YxiServerKey([string]$PublicKey) {
    if ($PublicKey -notmatch '^ssh-ed25519 ([A-Za-z0-9+/]+={0,2})$') { throw 'Invalid server public key' }
    $blob = [Convert]::FromBase64String($Matches[1])
    if ($blob.Length -ne 51 -or [BitConverter]::ToString($blob[0..3]) -ne '00-00-00-0B' -or
        [Text.Encoding]::ASCII.GetString($blob,4,11) -ne 'ssh-ed25519' -or
        [BitConverter]::ToString($blob[15..18]) -ne '00-00-00-20') { throw 'Invalid Ed25519 public key encoding' }
}
function Assert-YxiRegularPath([string]$Path) {
    if ((Test-Path -LiteralPath $Path) -and ((Get-Item -LiteralPath $Path).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked SSH file is unsupported' }
}
function Write-YxiAuthorizedKey([string]$Path, [string]$PublicKey, [Security.AccessControl.FileSecurity]$Acl) {
    Assert-YxiServerKey $PublicKey
    $Path = [IO.Path]::GetFullPath($Path)
    $backup = $Path + '.yxi.bak'
    $lockPath = $Path + '.yxi.lock'
    Assert-YxiRegularPath (Split-Path -Parent $Path)
    Assert-YxiRegularPath $lockPath
    $lock = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not $lock) {
        try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
        catch [IO.IOException] { if ([DateTime]::UtcNow -ge $deadline) { throw 'Another SSH key update is still running' }; Start-Sleep -Milliseconds 100 }
    }
    $temp = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        Assert-YxiRegularPath $Path
        Assert-YxiRegularPath $backup
        $old = if ([IO.File]::Exists($Path)) { [IO.File]::ReadAllText($Path) } else { '' }
        if ([IO.File]::Exists($Path)) { [IO.File]::SetAccessControl($Path, $Acl) }
        if (($old -split '\r?\n') | Where-Object { $_ -eq $PublicKey -or $_.StartsWith($PublicKey + ' ') }) { return }
        $next = $old + $(if ($old -and -not $old.EndsWith("`n")) { "`r`n" } else { '' }) + $PublicKey + " yxi-link`r`n"
        [IO.File]::WriteAllText($temp, $next, (New-Object Text.UTF8Encoding($false)))
        [IO.File]::SetAccessControl($temp, $Acl)
        if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temp, $Path, $backup) }
        else { [IO.File]::Move($temp, $Path) }
    } finally {
        if ([IO.File]::Exists($temp)) { [IO.File]::Delete($temp) }
        $lock.Dispose()
    }
}
if ($Mode -eq 'library') { return }
function Inspect-Local {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $service = Get-Service sshd -ErrorAction SilentlyContinue
    $hostPath = Join-Path $env:ProgramData 'ssh\ssh_host_ed25519_key.pub'
    $hostKey = ''
    try { if (Test-Path -LiteralPath $hostPath) { $hostKey = (Get-Content -LiteralPath $hostPath -Raw).Trim() } } catch { }
    [ordered]@{ username = $env:USERNAME; sid = $identity.User.Value; profile = $env:USERPROFILE;
        administrator = @($identity.Groups.Value) -contains 'S-1-5-32-544';
        service = if ($service) { [string]$service.Status } else { 'NotInstalled' };
        hostKey = $hostKey }
}
if ($Mode -eq 'inspect') { Inspect-Local | ConvertTo-Json -Compress; exit 0 }
$req = Get-Content -LiteralPath $RequestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$resultPath = Join-Path (Split-Path -Parent $RequestPath) 'result.json'
try {
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $admin) { throw 'Administrator permission is required' }
    $profile = Get-CimInstance Win32_UserProfile | Where-Object { $_.SID -eq $req.sid } | Select-Object -First 1
    if (-not $profile -or [IO.Path]::GetFullPath($profile.LocalPath) -ne [IO.Path]::GetFullPath($req.profile)) { throw 'Original user profile could not be verified' }
    Assert-YxiServerKey $req.publicKey
    if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) {
        Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
    }
    $service = Get-CimInstance Win32_Service -Filter "Name='sshd'"
    if (-not $service) { throw 'OpenSSH Server is not installed; check Windows optional feature download access' }
    $exe = if ($service.PathName -match '^"([^"]+)"') { $Matches[1] } else { ($service.PathName -split '\s+')[0] }
    $keygen = Join-Path (Split-Path -Parent $exe) 'ssh-keygen.exe'
    & $keygen -A
    if ($LASTEXITCODE -ne 0) { throw 'SSH host key generation failed' }
    Set-Service sshd -StartupType Automatic
    Start-Service sshd
    $sshDir = Join-Path $req.profile '.ssh'
    if ($req.administrator) { $sshDir = Join-Path $env:ProgramData 'ssh'; $name = 'administrators_authorized_keys' }
    else { $name = 'authorized_keys' }
    if ((Test-Path -LiteralPath $sshDir) -and ((Get-Item -LiteralPath $sshDir).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked SSH directory is unsupported' }
    New-Item -ItemType Directory -Path $sshDir -Force | Out-Null
    $keys = Join-Path $sshDir $name
    if ((Test-Path -LiteralPath $keys) -and ((Get-Item -LiteralPath $keys).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked authorized_keys is unsupported' }
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $sids = if ($req.administrator) { @('S-1-5-18','S-1-5-32-544') } else { @('S-1-5-18', [string]$req.sid) }
    foreach ($sid in $sids) {
        $principal = New-Object Security.Principal.SecurityIdentifier($sid)
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($principal, 'FullControl', 'Allow')))
    }
    Write-YxiAuthorizedKey -Path $keys -PublicKey $req.publicKey -Acl $acl
    $hostKey = (Get-Content -LiteralPath (Join-Path $env:ProgramData 'ssh\ssh_host_ed25519_key.pub') -Raw).Trim()
    $result = @{ ok = $true; username = $req.username; hostKey = $hostKey; service = [string](Get-Service sshd).Status }
} catch { $result = @{ ok = $false; error = $_.Exception.Message } }
[IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding($false)))
if (-not $result.ok) { exit 1 }
