param([string]$Classes,[string]$Work,[string]$Output)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot
$securePassword=(Get-Content -LiteralPath (Join-Path $taskRoot 'signing\password.dpapi') -Raw -Encoding UTF8).Trim() | ConvertTo-SecureString
$oldPassword=$env:TERMLENS_PROBE_SIGN_PASSWORD
try {
    $env:TERMLENS_PROBE_SIGN_PASSWORD=[System.Net.NetworkCredential]::new('', $securePassword).Password
    & 'C:\Users\微霖\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -X utf8 (Join-Path $PSScriptRoot 'build_checks.py') --classes $Classes --work $Work --output $Output
    if($LASTEXITCODE -ne 0){throw 'Compatibility checks build failed'}
}finally{$env:TERMLENS_PROBE_SIGN_PASSWORD=$oldPassword}
