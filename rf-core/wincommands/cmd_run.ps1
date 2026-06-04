#Requires -Version 7.0
# cmd_run.ps1 [<name>|all] [--features X] [--release] [--no-gpu]
#
# Runs examples under examples/*.rs.
#
# For a single example the binary output is streamed live; for the
# all variant each example runs in turn and only its one line
# summary is shown. Failed examples then have their last 12 lines
# printed for diagnosis.
param([Parameter(ValueFromRemainingArguments)][string[]]$RawArgs)

Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

$target = "all"
$features = "gpu_tests"
$release = $false

for ($i = 0; $i -lt $RawArgs.Count; $i++) {
    switch ($RawArgs[$i]) {
        "--features" { $i++; if ($i -lt $RawArgs.Count) { $features = $RawArgs[$i] } }
        "--no-gpu"   { $features = ($features -split "," | Where-Object { $_ -ne "gpu_tests" }) -join "," }
        "--release"  { $release = $true }
        default      { if ($RawArgs[$i] -notmatch "^-") { $target = $RawArgs[$i] } }
    }
}

$labelExtra = ""
if ($features) { $labelExtra += " features=$features" }
if ($release)  { $labelExtra += " release" }
Write-CmdHeader "run" "[$target]$labelExtra"

$available = Find-RustExamples
if ($available.Count -eq 0) {
    Write-Host "    no examples found in examples/" -ForegroundColor Yellow
    return
}

if ($target -ne "all" -and $target -notin $available) {
    Write-Host "    example '$target' not found" -ForegroundColor Red
    Write-Host "    available:" -ForegroundColor DarkGray
    foreach ($e in $available) { Write-Host "      $e" -ForegroundColor DarkGray }
    return
}

# Build cargo args. We build only, then run binaries directly.
if ($target -eq "all") {
    $cargoArgs = @("build", "--examples", "--message-format=json")
} else {
    $cargoArgs = @("build", "--example", $target, "--message-format=json")
}
if ($features) { $cargoArgs += "--features"; $cargoArgs += $features }
if ($release)  { $cargoArgs += "--release" }

$label = if ($target -eq "all") { "compile $($available.Count) examples" } else { "compile $target" }
$compileResult = Invoke-CargoWithProgress -Label $label -CargoArgs $cargoArgs -ShowProgress $true -ShowOutput $false

$parsed = Parse-CargoJsonOutput -Lines $compileResult.StdoutLines

if (-not $compileResult.Success) {
    Format-CompileErrors -Errors $parsed.Errors
    Format-WarningSummary -Warnings $parsed.Warnings
    return
}

$exampleArtifacts = @($parsed.Artifacts | Where-Object {
    $_.Kind -contains "example" -and ($target -eq "all" -or $_.Name -eq $target)
})

if ($exampleArtifacts.Count -eq 0) {
    Write-Host ""
    Write-Host "    no example binaries produced" -ForegroundColor Yellow
    Format-WarningSummary -Warnings $parsed.Warnings
    return
}

$exampleArtifacts = $exampleArtifacts | Sort-Object Name

# Execution phase.
Write-Host ""
Write-Host "    Running:" -ForegroundColor White
Write-Host ""

$nameWidth = ($exampleArtifacts | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
$nameWidth = [math]::Max($nameWidth, 24)

$streamLive = ($target -ne "all") -and ($exampleArtifacts.Count -eq 1)

$results = @()
foreach ($art in $exampleArtifacts) {
    if ($streamLive) {
        Write-Host "    [$($art.Name)] output:" -ForegroundColor Cyan
        $r = Invoke-ExampleBinary -Name $art.Name -ExePath $art.Executable -StreamOutput $true
        Write-Host ""
    } else {
        Write-Host -NoNewline "      $($art.Name.PadRight($nameWidth)) ... " -ForegroundColor Gray
        $r = Invoke-ExampleBinary -Name $art.Name -ExePath $art.Executable -StreamOutput $false
    }
    $results += $r

    $statusColor = switch ($r.Status) {
        "ok"      { "Green" }
        "FAILED"  { "Red" }
        "MISSING" { "Yellow" }
        default   { "Gray" }
    }

    if (-not $streamLive) {
        Write-Host -NoNewline $r.Status.PadRight(7) -ForegroundColor $statusColor
        $dur = Format-Duration $r.Duration
        Write-Host -NoNewline "  $dur" -ForegroundColor DarkGray
        if ($r.Summary) { Write-Host "  $($r.Summary)" -ForegroundColor White }
        else { Write-Host "" }
    } else {
        Write-Host "    status: " -NoNewline -ForegroundColor Gray
        Write-Host $r.Status -NoNewline -ForegroundColor $statusColor
        Write-Host "  $(Format-Duration $r.Duration)" -ForegroundColor DarkGray
    }
}

# Aggregate.
$okCount = @($results | Where-Object { $_.Status -eq "ok" }).Count
$failCount = @($results | Where-Object { $_.Status -ne "ok" }).Count
$totalDuration = ($results | Measure-Object -Property Duration -Sum).Sum

Write-Host ""
$summaryColor = if ($failCount -gt 0) { "Red" } else { "Green" }
$durStr = Format-Duration $totalDuration
Write-Host "    Total: $okCount ok, $failCount failed | $durStr" -ForegroundColor $summaryColor

# Failure tail for examples that died.
$failedResults = @($results | Where-Object { $_.Status -ne "ok" -and $_.Status -ne "MISSING" })
if ($failedResults.Count -gt 0 -and -not $streamLive) {
    Write-Host ""
    Write-Host "    Failure output:" -ForegroundColor Red
    foreach ($f in $failedResults) {
        Write-Host "      $($f.Name) (exit $($f.ExitCode)):" -ForegroundColor Red
        $tail = $f.Output | Select-Object -Last 12
        foreach ($line in $tail) {
            Write-Host "        $line" -ForegroundColor DarkRed
        }
    }
    $global:LASTEXITCODE = 1
}

Format-WarningSummary -Warnings $parsed.Warnings