#Requires -Version 7.0
# cmd_unlock.ps1 - Kill stuck cargo processes and release target/ lock.
param()

Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

Write-CmdHeader "unlock" "kill stuck cargo/rustc processes"

$cargoProcs = Get-Process -Name "cargo" -ErrorAction SilentlyContinue
$rustcProcs = Get-Process -Name "rustc" -ErrorAction SilentlyContinue
$ccProcs = Get-Process -Name "cc1","cc1plus","link" -ErrorAction SilentlyContinue

$allProcs = @()
if ($cargoProcs) { $allProcs += $cargoProcs }
if ($rustcProcs) { $allProcs += $rustcProcs }
if ($ccProcs)    { $allProcs += $ccProcs }

if ($allProcs.Count -eq 0) {
    Write-Host "    no stuck cargo/rustc processes" -ForegroundColor Green
    $lockFile = Join-Path (Get-Location) "target\.cargo-lock"
    if (Test-Path $lockFile) {
        Write-Host "    found stale lock file, removing..." -ForegroundColor Yellow
        Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
        Write-Host "    lock file removed" -ForegroundColor Green
    }
    return
}

Write-Host "    found $($allProcs.Count) process(es):" -ForegroundColor Yellow
foreach ($p in $allProcs) {
    $age = ((Get-Date) - $p.StartTime).TotalSeconds
    $ageStr = if ($age -gt 60) { "$([math]::Floor($age / 60))m" } else { "$([math]::Floor($age))s" }
    Write-Host "      PID $($p.Id)  $($p.ProcessName)  age=$ageStr  cpu=$([math]::Round($p.CPU, 1))s" -ForegroundColor DarkYellow
}

Write-Host ""
Write-Host -NoNewline "    kill all? [y/N] " -ForegroundColor Yellow
$confirm = Read-Host

if ($confirm -eq "y" -or $confirm -eq "Y") {
    foreach ($p in $allProcs) {
        try {
            taskkill /F /T /PID $p.Id 2>&1 | Out-Null
            Write-Host "    killed PID $($p.Id) ($($p.ProcessName))" -ForegroundColor Green
        } catch {
            Write-Host "    failed to kill PID $($p.Id): $_" -ForegroundColor Red
        }
    }
    $lockFile = Join-Path (Get-Location) "target\.cargo-lock"
    if (Test-Path $lockFile) {
        Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
        Write-Host "    stale lock file removed" -ForegroundColor Green
    }
    Write-Host "    done. try building again." -ForegroundColor Green
} else {
    Write-Host "    cancelled" -ForegroundColor DarkGray
}