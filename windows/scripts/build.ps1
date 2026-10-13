<#
.SYNOPSIS
  Restores, builds, tests and publishes the Ali Control Windows agent.

.DESCRIPTION
  Produces a self-contained, single-file, x64 AliControl.exe (≈135 MB, uncompressed on
  purpose: compression would shrink the file to ≈55 MB but roughly double the resident
  memory of the always-running tray app) — no .NET runtime and no
  installer needed on the target PC. The output is:

      windows\dist\AliControl\AliControl.exe

  Run from anywhere:   powershell -ExecutionPolicy Bypass -File windows\scripts\build.ps1

.PARAMETER Configuration   Release (default) or Debug.
.PARAMETER SkipTests       Skip `dotnet test` (the tests never touch the real PC either way).
.PARAMETER FrameworkDependent
  Publish a small (~1 MB) build that needs the .NET 10 Desktop Runtime installed on the
  target PC instead of bundling it.
#>
[CmdletBinding()]
param(
    [ValidateSet('Release', 'Debug')] [string] $Configuration = 'Release',
    [switch] $SkipTests,
    [switch] $FrameworkDependent
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root

function Invoke-Step([string] $Title, [scriptblock] $Action) {
    Write-Host "==> $Title" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Step failed: $Title (exit code $LASTEXITCODE)" }
}

Invoke-Step 'dotnet restore' { dotnet restore AliControl.slnx }
Invoke-Step "dotnet build ($Configuration)" { dotnet build AliControl.slnx -c $Configuration --no-restore }
if (-not $SkipTests) {
    Invoke-Step 'dotnet test' { dotnet test AliControl.slnx -c $Configuration --no-build }
}

$out = Join-Path $root 'dist\AliControl'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }

$selfContained = if ($FrameworkDependent) { 'false' } else { 'true' }
Invoke-Step "dotnet publish (win-x64, self-contained=$selfContained)" {
    dotnet publish AliControlWindows\AliControlWindows.csproj `
        -c $Configuration -r win-x64 --self-contained $selfContained `
        -p:PublishSingleFile=true `
        -p:EnableCompressionInSingleFile=false `
        -p:DebugType=none -p:DebugSymbols=false `
        -o $out
}

$exe = Join-Path $out 'AliControl.exe'
if (-not (Test-Path $exe)) { throw "Publish finished but $exe was not produced." }
$sizeMb = [math]::Round((Get-Item $exe).Length / 1MB, 1)
Write-Host ''
Write-Host "Built: $exe  ($sizeMb MB)" -ForegroundColor Green
Write-Host 'Run it by double-clicking; it lives in the notification area (tray). Use  AliControl.exe --diagnose  for a setup report.'
