# cmd_status.ps1
Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

Write-CmdHeader "status" ""

# Git block.
Write-Host "    Git" -ForegroundColor White
$branch = (git branch --show-current 2>&1) -join ""
$ahead = git rev-list --count "@{u}..HEAD" 2>&1
$behind = git rev-list --count "HEAD..@{u}" 2>&1
$dirty = @(git status --porcelain 2>&1).Count

Write-Host "      Branch:   $branch" -ForegroundColor Gray
if ($ahead -match "^\d+$" -and [int]$ahead -gt 0) {
    Write-Host "      Ahead:    $ahead commit(s)" -ForegroundColor Yellow
}
if ($behind -match "^\d+$" -and [int]$behind -gt 0) {
    Write-Host "      Behind:   $behind commit(s)" -ForegroundColor Yellow
}
Write-Host "      Dirty:    $dirty file(s)" -ForegroundColor $(if ($dirty -gt 0) { "Yellow" } else { "Green" })

$lastCommit = git log -1 --format="%h %s (%cr)" 2>&1
Write-Host "      Last:     $lastCommit" -ForegroundColor DarkGray
Write-Host ""

# Build block.
Write-Host "    Build" -ForegroundColor White
$debugExists = (Get-ChildItem "target\debug\librf_core*.rlib" -ErrorAction SilentlyContinue).Count -gt 0
$releaseExists = (Get-ChildItem "target\release\librf_core*.rlib" -ErrorAction SilentlyContinue).Count -gt 0

Write-Host "      Debug:    $(if ($debugExists) { "built" } else { "not built" })" -ForegroundColor $(if ($debugExists) { "Green" } else { "DarkGray" })
Write-Host "      Release:  $(if ($releaseExists) { "built" } else { "not built" })" -ForegroundColor $(if ($releaseExists) { "Green" } else { "DarkGray" })

if (Test-Path "target") {
    $targetSize = [math]::Round((Get-ChildItem "target" -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host "      target/:  ${targetSize} MiB" -ForegroundColor DarkGray
}

if (Test-Path "target\hertzian_out") {
    $outFiles = (Get-ChildItem "target\hertzian_out" -Recurse -File -ErrorAction SilentlyContinue).Count
    Write-Host "      dumps:    $outFiles file(s) under target\hertzian_out" -ForegroundColor DarkGray
}
Write-Host ""