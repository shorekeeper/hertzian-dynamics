#Requires -Version 7.0
# cmd_test.ps1 [<name>|all] [--features X] [--filter X] [--no-gpu]
#
# Runs integration tests under tests/*.rs.
#
# Workflow:
#   1. Discover available test files.
#   2. Compile (cargo test --no-run --message-format=json) with the
#      progress bar visible and all compiler output hidden.
#   3. Parse the JSON stream for test binary paths and warnings.
#   4. Execute each test binary, parse its libtest result line, and
#      print a per-test status line.
#   5. Print the grouped warning summary.
param([Parameter(ValueFromRemainingArguments)][string[]]$RawArgs)

Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

$target = "all"
$features = "gpu_tests"
$filter = $null

for ($i = 0; $i -lt $RawArgs.Count; $i++) {
    switch ($RawArgs[$i]) {
        "--features" { $i++; if ($i -lt $RawArgs.Count) { $features = $RawArgs[$i] } }
        "--no-gpu"   { $features = ($features -split "," | Where-Object { $_ -ne "gpu_tests" }) -join "," }
        "--filter"   { $i++; if ($i -lt $RawArgs.Count) { $filter = $RawArgs[$i] } }
        default      { if ($RawArgs[$i] -notmatch "^-") { $target = $RawArgs[$i] } }
    }
}

$labelExtra = ""
if ($features) { $labelExtra += " features=$features" }
if ($filter)   { $labelExtra += " filter=$filter" }
Write-CmdHeader "test" "[$target]$labelExtra"

$available = Find-RustTests
if ($available.Count -eq 0) {
    Write-Host "    no tests found in tests/" -ForegroundColor Yellow
    return
}

if ($target -ne "all" -and $target -notin $available) {
    Write-Host "    test '$target' not found" -ForegroundColor Red
    Write-Host "    available:" -ForegroundColor DarkGray
    foreach ($t in $available) { Write-Host "      $t" -ForegroundColor DarkGray }
    return
}

# Build cargo args.
if ($target -eq "all") {
    $cargoArgs = @("test", "--tests", "--no-run", "--no-fail-fast", "--message-format=json")
} else {
    $cargoArgs = @("test", "--test", $target, "--no-run", "--message-format=json")
}
if ($features) { $cargoArgs += "--features"; $cargoArgs += $features }

# Compile phase: progress bar on, compiler output suppressed.
$label = if ($target -eq "all") { "compile $($available.Count) tests" } else { "compile $target" }
$compileResult = Invoke-CargoWithProgress -Label $label -CargoArgs $cargoArgs -ShowProgress $true -ShowOutput $false

# Parse JSON for artifacts and diagnostics.
$parsed = Parse-CargoJsonOutput -Lines $compileResult.StdoutLines

if (-not $compileResult.Success) {
    Format-CompileErrors -Errors $parsed.Errors
    Format-WarningSummary -Warnings $parsed.Warnings
    return
}

$testArtifacts = @($parsed.Artifacts | Where-Object {
    $_.Kind -contains "test" -and ($target -eq "all" -or $_.Name -eq $target)
})

if ($testArtifacts.Count -eq 0) {
    Write-Host ""
    Write-Host "    no test binaries produced (build succeeded but nothing to run)" -ForegroundColor Yellow
    Format-WarningSummary -Warnings $parsed.Warnings
    return
}

$testArtifacts = $testArtifacts | Sort-Object Name

# Execution phase.
Write-Host ""
Write-Host "    Running:" -ForegroundColor White
Write-Host ""

$nameWidth = ($testArtifacts | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
$nameWidth = [math]::Max($nameWidth, 24)

$results = @()
foreach ($art in $testArtifacts) {
    Write-Host -NoNewline "      $($art.Name.PadRight($nameWidth)) ... " -ForegroundColor Gray
    $r = Invoke-TestBinary -Name $art.Name -ExePath $art.Executable -Filter $filter
    $results += $r

    $statusColor = switch ($r.Status) {
        "ok"      { "Green" }
        "EMPTY"   { "DarkGray" }
        "FAILED"  { "Red" }
        "MISSING" { "Yellow" }
        default   { "Gray" }
    }
    $statusText = if ($r.Status -eq "EMPTY") { "ok" } else { $r.Status }
    Write-Host -NoNewline $statusText.PadRight(7) -ForegroundColor $statusColor

    if ($r.Status -eq "EMPTY") {
        Write-Host "  (no tests)" -ForegroundColor DarkGray
    } else {
        $parts = @()
        if ($r.Passed -gt 0)  { $parts += "$($r.Passed) passed" }
        if ($r.Failed -gt 0)  { $parts += "$($r.Failed) failed" }
        if ($r.Ignored -gt 0) { $parts += "$($r.Ignored) ignored" }
        $detail = $parts -join ", "
        $dur = Format-Duration $r.Duration
        Write-Host "  $detail" -NoNewline -ForegroundColor DarkGray
        Write-Host "  $dur" -ForegroundColor DarkGray
    }
}

# Aggregate.
$totalPassed   = ($results | Measure-Object -Property Passed -Sum).Sum
$totalFailed   = ($results | Measure-Object -Property Failed -Sum).Sum
$totalIgnored  = ($results | Measure-Object -Property Ignored -Sum).Sum
$totalDuration = ($results | Measure-Object -Property Duration -Sum).Sum

Write-Host ""
$summaryColor = if ($totalFailed -gt 0) { "Red" } else { "Green" }
$durStr = Format-Duration $totalDuration
Write-Host "    Total: $totalPassed passed, $totalFailed failed, $totalIgnored ignored | $durStr" -ForegroundColor $summaryColor

# Failure detail block.
$failedResults = @($results | Where-Object { $_.Status -eq "FAILED" })
if ($failedResults.Count -gt 0) {
    Write-Host ""
    Write-Host "    Failures:" -ForegroundColor Red
    foreach ($f in $failedResults) {
        Write-Host "      $($f.Name):" -ForegroundColor Red
        foreach ($d in ($f.FailureDetails | Select-Object -First 8)) {
            Write-Host "        $d" -ForegroundColor DarkRed
        }
        if ($f.FailureDetails.Count -gt 8) {
            Write-Host "        ... +$($f.FailureDetails.Count - 8) more lines" -ForegroundColor DarkGray
        }
    }
    $global:LASTEXITCODE = 1
}

Format-WarningSummary -Warnings $parsed.Warnings