# _common.ps1 - Shared helpers for all commands.

function Format-Duration {
    param([double]$Ms)
    if ($Ms -lt 1000) { return "$([math]::Round($Ms))ms" }
    if ($Ms -lt 60000) { return "$([math]::Round($Ms / 1000, 1))s" }
    $m = [math]::Floor($Ms / 60000)
    $s = [math]::Round(($Ms % 60000) / 1000, 1)
    return "${m}m ${s}s"
}

function Write-CmdHeader {
    param([string]$Name, [string]$Desc)
    Write-Host "  $Name" -ForegroundColor Cyan -NoNewline
    Write-Host " $Desc" -ForegroundColor DarkGray
    Write-Host "  $("-" * 60)" -ForegroundColor DarkGray
}

function Write-SubStep {
    param([string]$Label, [string]$Status, [string]$Detail = "")
    $color = switch ($Status) {
        "OK"   { "Green" }
        "FAIL" { "Red" }
        "SKIP" { "Yellow" }
        "WARN" { "Yellow" }
        "RUN"  { "DarkGray" }
        default { "Gray" }
    }
    Write-Host -NoNewline "    $Label ... " -ForegroundColor White
    Write-Host -NoNewline $Status -ForegroundColor $color
    if ($Detail) { Write-Host " $Detail" -ForegroundColor DarkGray }
    else { Write-Host "" }
}

function Get-RsFileStats {
    # Scans src/ relative to cwd (which is rf-core after shell.ps1 cd).
    $files = Get-ChildItem -Path "src" -Recurse -Filter "*.rs" -ErrorAction SilentlyContinue
    $totalLines = 0
    $docLines = 0
    $codeLines = 0
    foreach ($f in $files) {
        $content = Get-Content $f.FullName
        $totalLines += $content.Count
        foreach ($l in $content) {
            if ($l -match "^\s*///|^\s*//!") { $docLines++ }
            elseif ($l.Trim() -and $l.Trim() -notmatch "^//") { $codeLines++ }
        }
    }
    return [PSCustomObject]@{
        Files     = $files.Count
        Total     = $totalLines
        Code      = $codeLines
        Doc       = $docLines
        DocPct    = if ($totalLines -gt 0) { [math]::Round($docLines / $totalLines * 100, 1) } else { 0 }
    }
}