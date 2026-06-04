#Requires -Version 7.0
# cmd_build.ps1 [validation] [--release] [--features X,Y]
param([Parameter(ValueFromRemainingArguments)][string[]]$RawArgs)

Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

$features = ""
$release = $false

for ($i = 0; $i -lt $RawArgs.Count; $i++) {
    switch ($RawArgs[$i]) {
        "release"    { $release = $true }
        "--release"  { $release = $true }
        "--features" { $i++; if ($i -lt $RawArgs.Count) { $features = $RawArgs[$i] } }
        "validation" { $features = "validation" }
        default      { if ($RawArgs[$i] -notmatch "^-") { $features = $RawArgs[$i] } }
    }
}

$label = $features ? $features : "no features"
$mode = $release ? "release" : "dev"
Write-CmdHeader "build" "[$label] [$mode]"

$cargoArgs = @("build", "--lib")
if ($features) { $cargoArgs += "--features"; $cargoArgs += $features }
if ($release)  { $cargoArgs += "--release" }

$result = Invoke-CargoWithProgress -Label "build $label" -CargoArgs $cargoArgs -ShowProgress $true -ShowOutput $false

if ($result.Success) {
    if ($result.Warnings.Count -gt 0) {
        Write-Host ""
        Write-Host "    $($result.Warnings.Count) warning(s):" -ForegroundColor Yellow
        $result.Warnings | Select-Object -First 10 | ForEach-Object {
            if ($_ -match "warning:\s*(.+)") {
                Write-Host "      $($Matches[1])" -ForegroundColor DarkYellow
            }
        }
    }

    $targetDir = $release ? "target\release" : "target\debug"
    $rlib = Get-ChildItem -Path $targetDir -Filter "librf_core*.rlib" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($rlib) {
        $sizeKB = [math]::Round($rlib.Length / 1024, 1)
        Write-Host "    output: $($rlib.Name) (${sizeKB} KiB)" -ForegroundColor DarkGray
    }
} else {
    Write-Host ""
    foreach ($e in ($result.Errors | Select-Object -First 5)) {
        Write-Host "    $e" -ForegroundColor Red
    }
    $global:LASTEXITCODE = 1
}