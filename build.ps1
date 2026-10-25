param(
    [string]$ToolRoot = 'D:\codex-tools\android-build',
    [string]$Python = 'C:\Users\微霖\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe',
    [string]$Output = (Join-Path $PSScriptRoot 'APK'),
    [string]$WorkRoot = (Join-Path $PSScriptRoot 'build/work')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) { throw 'Pass a valid Python executable with -Python.' }
$signingDirectory = Join-Path $PSScriptRoot 'signing'
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$passwordFile = Join-Path $signingDirectory 'password.dpapi'
if (-not (Test-Path -LiteralPath $passwordFile -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $signingDirectory 'probe.p12') -PathType Leaf)) {
    throw 'Original signing/probe.p12 and password.dpapi are required. Run on the original signing computer; never generate a replacement key for an upgrade.'
}
$securePassword = (Get-Content -LiteralPath $passwordFile -Raw -Encoding UTF8).Trim() | ConvertTo-SecureString
$previousPassword = $env:TERMLENS_PROBE_SIGN_PASSWORD
try {
    $env:TERMLENS_PROBE_SIGN_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
    & $Python -X utf8 (Join-Path $PSScriptRoot 'build.py') --tools $ToolRoot --output $Output --work-root $WorkRoot
    if ($LASTEXITCODE -ne 0) { throw 'TermLens probe APK build failed.' }
} finally {
    $env:TERMLENS_PROBE_SIGN_PASSWORD = $previousPassword
}
