#Requires -Version 7.0
# _runner.ps1 - Helpers for batch test and example execution.
#
# The flow for both tests and examples:
#   1. cargo with --message-format=json builds all target binaries
#      in one go. The progress bar (from _progress.ps1) reads
#      stderr for "Compiling X" lines; the JSON stream on stdout
#      carries structured artifact and diagnostic data.
#   2. After the build, parse the JSON stream to collect:
#        - artifact entries with their executable paths
#        - warning and error diagnostics with target attribution
#   3. Execute each binary directly (no second cargo invocation
#      needed), capture its output, and parse libtest result lines
#      for tests or pick a one line summary for examples.
#   4. Print a grouped warning summary at the end.

function Find-RustTests {
    # Returns sorted file stems of every tests/*.rs in cwd.
    $testsDir = Join-Path (Get-Location) "tests"
    if (-not (Test-Path $testsDir)) { return @() }
    Get-ChildItem $testsDir -Filter "*.rs" -File | ForEach-Object {
        [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
    } | Sort-Object
}

function Find-RustExamples {
    # Returns sorted file stems of every examples/*.rs in cwd.
    $exDir = Join-Path (Get-Location) "examples"
    if (-not (Test-Path $exDir)) { return @() }
    Get-ChildItem $exDir -Filter "*.rs" -File | ForEach-Object {
        [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
    } | Sort-Object
}

function Parse-CargoJsonOutput {
    # Given a list of lines from cargo --message-format=json, extract
    # artifacts (with executable paths) and diagnostics (warnings,
    # errors) plus the build-finished flag.
    param([string[]]$Lines)

    $artifacts = [System.Collections.ArrayList]::new()
    $warnings = [System.Collections.ArrayList]::new()
    $errors = [System.Collections.ArrayList]::new()
    $buildFinished = $null

    foreach ($line in $Lines) {
        if (-not $line) { continue }
        if ($line[0] -ne '{') { continue }
        $obj = $null
        try { $obj = $line | ConvertFrom-Json -Depth 30 } catch { continue }
        if (-not $obj -or -not $obj.reason) { continue }

        switch ($obj.reason) {
            "compiler-artifact" {
                if ($obj.executable) {
                    $null = $artifacts.Add([PSCustomObject]@{
                        Name       = $obj.target.name
                        Kind       = @($obj.target.kind)
                        Executable = $obj.executable
                        Fresh      = [bool]$obj.fresh
                    })
                }
            }
            "compiler-message" {
                $msg = $obj.message
                if (-not $msg) { break }
                $level = $msg.level
                # Drop noise: summary "N warnings emitted", "aborting due to ..."
                if ($msg.message -match "\d+ warnings? emitted") { break }
                if ($msg.message -match "aborting due to") { break }
                if ($msg.message -match "For more information about this error") { break }

                $firstSpan = $null
                if ($msg.spans -and $msg.spans.Count -gt 0) {
                    foreach ($s in $msg.spans) {
                        if ($s.is_primary) { $firstSpan = $s; break }
                    }
                    if (-not $firstSpan) { $firstSpan = $msg.spans[0] }
                }

                $entry = [PSCustomObject]@{
                    TargetName = $obj.target.name
                    TargetKind = @($obj.target.kind)
                    Level      = $level
                    Message    = $msg.message
                    Code       = if ($msg.code) { $msg.code.code } else { "" }
                    File       = if ($firstSpan) { $firstSpan.file_name } else { "" }
                    Line       = if ($firstSpan) { [int]$firstSpan.line_start } else { 0 }
                    Rendered   = $msg.rendered
                }
                if ($level -eq "warning") { $null = $warnings.Add($entry) }
                elseif ($level -eq "error") { $null = $errors.Add($entry) }
            }
            "build-finished" {
                $buildFinished = [bool]$obj.success
            }
        }
    }

    return [PSCustomObject]@{
        Artifacts     = [PSCustomObject[]]$artifacts.ToArray()
        Warnings      = [PSCustomObject[]]$warnings.ToArray()
        Errors        = [PSCustomObject[]]$errors.ToArray()
        BuildFinished = $buildFinished
    }
}

function Format-WarningSummary {
    # Prints the "N warns overall" + per-target breakdown block.
    # Empty input is a no-op.
    param([PSCustomObject[]]$Warnings)

    if (-not $Warnings -or $Warnings.Count -eq 0) { return }

    Write-Host ""
    Write-Host "    $($Warnings.Count) warns overall." -ForegroundColor Yellow
    $groups = $Warnings | Group-Object -Property TargetName | Sort-Object Count -Descending
    foreach ($g in $groups) {
        $name = if ($g.Name) { $g.Name } else { "(unknown)" }
        Write-Host "    $($g.Count) warns in: $name" -ForegroundColor DarkYellow
    }
}

function Invoke-TestBinary {
    # Run one libtest executable and parse the "test result:" line.
    # Returns counts plus collected failure text for the caller.
    param([string]$Name, [string]$ExePath, [string]$Filter)

    if (-not $ExePath -or -not (Test-Path $ExePath)) {
        return [PSCustomObject]@{
            Name = $Name; Status = "MISSING"; Passed = 0; Failed = 0; Ignored = 0
            Duration = 0; Output = @(); FailureDetails = @(); ExitCode = -1
        }
    }

    $libtestArgs = @()
    if ($Filter) { $libtestArgs += $Filter }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $output = @()
    try {
        $output = & $ExePath @libtestArgs 2>&1 | ForEach-Object { "$_" }
    } catch {
        $output = @("internal error: $($_.Exception.Message)")
    }
    $exitCode = $LASTEXITCODE
    $sw.Stop()

    $passed = 0; $failed = 0; $ignored = 0
    foreach ($line in $output) {
        if ($line -match "test result:\s*(ok|FAILED)\.\s*(\d+)\s+passed;\s*(\d+)\s+failed;\s*(\d+)\s+ignored") {
            $passed = [int]$Matches[2]
            $failed = [int]$Matches[3]
            $ignored = [int]$Matches[4]
        }
    }

    if ($exitCode -eq 0 -and $failed -eq 0) {
        $status = if ($passed -eq 0 -and $ignored -eq 0) { "EMPTY" } else { "ok" }
    } else {
        $status = "FAILED"
    }

    # Collect lines that look like failure detail (panic info etc).
    $details = @()
    $inFailures = $false
    foreach ($line in $output) {
        if ($line -match "^failures:\s*$") { $inFailures = $true; continue }
        if ($line -match "^test result:") { $inFailures = $false }
        if ($line -match "panicked at") {
            $details += $line.Trim()
            continue
        }
        if ($inFailures -and $line -match "^\s*test ") { continue }
        if ($inFailures -and $line.Trim()) { $details += $line }
    }

    return [PSCustomObject]@{
        Name = $Name
        Status = $status
        Passed = $passed
        Failed = $failed
        Ignored = $ignored
        Duration = $sw.Elapsed.TotalMilliseconds
        Output = $output
        FailureDetails = $details
        ExitCode = $exitCode
    }
}

function Invoke-ExampleBinary {
    # Run one example binary; capture stdout/stderr and try to pick a
    # short one line summary for display. Exit code 0 is ok.
    param([string]$Name, [string]$ExePath, [bool]$StreamOutput = $false)

    if (-not $ExePath -or -not (Test-Path $ExePath)) {
        return [PSCustomObject]@{
            Name = $Name; Status = "MISSING"; Duration = 0; Output = @()
            Summary = ""; ExitCode = -1
        }
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $output = @()
    try {
        if ($StreamOutput) {
            $output = & $ExePath 2>&1 | ForEach-Object {
                $line = "$_"
                Write-Host "      $line" -ForegroundColor Gray
                $line
            }
        } else {
            $output = & $ExePath 2>&1 | ForEach-Object { "$_" }
        }
    } catch {
        $output += "internal error: $($_.Exception.Message)"
    }
    $exitCode = $LASTEXITCODE
    $sw.Stop()

    $status = if ($exitCode -eq 0) { "ok" } else { "FAILED" }

    # Pick a useful one liner: prefer lines with key metrics.
    $summary = ""
    foreach ($line in $output) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed -match "^Outputs:|^output:|^dumps:|^Output:") { continue }
        if ($trimmed -match "SNR\s*=|Top bin|max abs error|device:|Noise floor|MHz|FSPL|loss|fade|MUF|total\s+\d") {
            $summary = $trimmed
        }
    }
    if (-not $summary -and $output.Count -gt 0) {
        for ($i = $output.Count - 1; $i -ge 0; $i--) {
            $t = $output[$i].Trim()
            if ($t -and $t -notmatch "^Outputs:|^output:") { $summary = $t; break }
        }
    }
    if ($summary.Length -gt 70) { $summary = $summary.Substring(0, 67) + "..." }

    return [PSCustomObject]@{
        Name = $Name
        Status = $status
        Duration = $sw.Elapsed.TotalMilliseconds
        Output = $output
        Summary = $summary
        ExitCode = $exitCode
    }
}

function Format-CompileErrors {
    # Pretty print errors collected from the JSON stream.
    param([PSCustomObject[]]$Errors)
    if (-not $Errors -or $Errors.Count -eq 0) { return }
    Write-Host ""
    Write-Host "    Compile errors:" -ForegroundColor Red
    foreach ($e in ($Errors | Select-Object -First 10)) {
        $loc = if ($e.File) { " ($($e.File):$($e.Line))" } else { "" }
        Write-Host "      [$($e.TargetName)] $($e.Message)$loc" -ForegroundColor Red
    }
    if ($Errors.Count -gt 10) {
        Write-Host "      ... +$($Errors.Count - 10) more" -ForegroundColor DarkGray
    }
}