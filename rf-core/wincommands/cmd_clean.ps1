# cmd_clean.ps1 [all|target|out]
# target = cargo target dir
# out    = target/hertzian_out (example dumps)
# all    = both
param([string]$What = "target")
Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

Write-CmdHeader "clean" "[$What]"

function Clean-Target {
    Write-Host -NoNewline "    cargo clean ... "
    cargo clean 2>&1 | Out-Null
    Write-Host "ok" -ForegroundColor Green
}

function Clean-Out {
    $outDir = Join-Path (Get-Location) "target\hertzian_out"
    if (Test-Path $outDir) {
        $count = (Get-ChildItem $outDir -Recurse -File -ErrorAction SilentlyContinue).Count
        Remove-Item $outDir -Recurse -Force
        Write-Host "    removed hertzian_out: $count file(s)" -ForegroundColor Green
    } else {
        Write-Host "    no hertzian_out to clean" -ForegroundColor DarkGray
    }
}

switch ($What) {
    "target" { Clean-Target }
    "out"    { Clean-Out }
    "all"    { Clean-Target; Clean-Out }
    default  { Write-Host "    unknown: $What (use target, out, all)" -ForegroundColor Red }
}