#Requires -Version 7.0
# _progress.ps1 - Real-time single-line progress rendering for cargo operations.
#
# Uses \r (carriage return) to overwrite one line in place.
# No cursor-up/down movement. Works in any terminal.

$script:AnsiE = [char]0x1b

function Hide-Cursor { Write-Host -NoNewline "$script:AnsiE[?25l" }
function Show-Cursor { Write-Host -NoNewline "$script:AnsiE[?25h" }

class ProgressBar {
    [string]$Label
    [int]$BarWidth
    [int]$Total
    [int]$Current
    [string]$Status
    [string]$Detail
    [System.Diagnostics.Stopwatch]$Timer
    [int]$SpinFrame

    static [string[]]$SpinChars = @("|","/","-","\")

    ProgressBar([string]$Label, [int]$BarWidth) {
        $this.Label = $Label
        $this.BarWidth = $BarWidth
        $this.Total = 0
        $this.Current = 0
        $this.Status = "starting"
        $this.Detail = ""
        $this.Timer = [System.Diagnostics.Stopwatch]::StartNew()
        $this.SpinFrame = 0
    }

    [string] RenderLine() {
        $e = [char]0x1b
        $sec = [math]::Floor($this.Timer.Elapsed.TotalSeconds)
        $timeStr = "${sec}s"

        if ($this.Total -gt 0 -and $this.Current -ge 0) {
            $pct = [math]::Min(100, [math]::Round(($this.Current / $this.Total) * 100))
            $filled = [math]::Round(($this.Current / $this.Total) * $this.BarWidth)
            $filled = [math]::Min($filled, $this.BarWidth)
            $empty = $this.BarWidth - $filled

            $color = if ($pct -ge 90) { "32" } elseif ($pct -ge 50) { "33" } else { "36" }
            $bar = "$e[${color}m$("=" * $filled)$e[2m$("-" * $empty)$e[0m"
            $indicator = "${pct}%"
            $countStr = "$($this.Current)/$($this.Total)"
        } else {
            $spin = [ProgressBar]::SpinChars[$this.SpinFrame % [ProgressBar]::SpinChars.Count]
            $this.SpinFrame++
            $bar = "$e[2m$("-" * $this.BarWidth)$e[0m"
            $indicator = $spin
            $countStr = ""
        }

        $statusColor = switch ($this.Status) {
            "compiling"   { "36" }
            "checking"    { "36" }
            "fetching"    { "35" }
            "linking"     { "33" }
            "testing"     { "34" }
            "documenting" { "34" }
            "done"        { "32" }
            default       { "2" }
        }
        $statusBadge = "$e[${statusColor}m$($this.Status)$e[0m"

        $det = $this.Detail
        if ($det.Length -gt 25) { $det = $det.Substring(0, 22) + "..." }

        return "  $($this.Label) [$bar] $indicator $countStr $statusBadge $e[2m$det $timeStr$e[0m"
    }
}

class CargoStreamer {
    [System.Collections.ArrayList]$AllOutput
    [System.Collections.ArrayList]$StdoutLines
    [System.Collections.ArrayList]$StderrLines
    [System.Collections.ArrayList]$Errors
    [System.Collections.ArrayList]$Warnings
    [ProgressBar]$Bar
    [int]$ExpectedCrates
    [int]$CratesCompiled
    [int]$CratesDownloaded
    [int]$DownloadTotal
    [bool]$DrawProgress
    [string]$Phase

    CargoStreamer([string]$Label, [bool]$ShowProgress) {
        $this.AllOutput = [System.Collections.ArrayList]::new()
        $this.StdoutLines = [System.Collections.ArrayList]::new()
        $this.StderrLines = [System.Collections.ArrayList]::new()
        $this.Errors = [System.Collections.ArrayList]::new()
        $this.Warnings = [System.Collections.ArrayList]::new()
        $this.Bar = [ProgressBar]::new($Label, 30)
        $this.ExpectedCrates = Get-ExpectedCrateCount
        $this.CratesCompiled = 0
        $this.CratesDownloaded = 0
        $this.DownloadTotal = 0
        $this.DrawProgress = $ShowProgress
        $this.Phase = "init"
        if ($this.ExpectedCrates -gt 0) {
            $this.Bar.Total = $this.ExpectedCrates
        }
    }

    [void] ParseLine([string]$Line, [string]$Origin) {
        $null = $this.AllOutput.Add($Line)
        if ($Origin -eq "stdout") { $null = $this.StdoutLines.Add($Line) }
        elseif ($Origin -eq "stderr") { $null = $this.StderrLines.Add($Line) }

        if ($Line -match "Downloading\s+(\d+)\s+crate") {
            $this.DownloadTotal = [int]$Matches[1]
            $this.Phase = "fetch"
            $this.Bar.Detail = "downloading $($this.DownloadTotal) crates..."
            $this.Bar.Status = "fetching"
            return
        }
        if ($Line -match "Downloaded\s+(\S+)\s+v") {
            $this.CratesDownloaded++
            $this.Phase = "fetch"
            $this.Bar.Detail = "fetch $($Matches[1])"
            $this.Bar.Status = "fetching"
            if ($this.DownloadTotal -gt 0) {
                $fetchPct = $this.CratesDownloaded / $this.DownloadTotal
                $this.Bar.Current = [math]::Round($fetchPct * $this.ExpectedCrates * 0.05)
            }
            return
        }
        if ($Line -match "Updating") {
            $this.Phase = "fetch"
            $this.Bar.Detail = "updating index"
            $this.Bar.Status = "updating"
            return
        }
        if ($Line -match "Compiling\s+(\S+)\s+v(\S+)") {
            $this.Phase = "compile"
            $this.CratesCompiled++
            $this.Bar.Detail = "$($Matches[1]) v$($Matches[2])"
            $this.Bar.Status = "compiling"
            $this.UpdateCompileProgress()
            return
        }
        if ($Line -match "Checking\s+(\S+)\s+v(\S+)") {
            $this.Phase = "compile"
            $this.CratesCompiled++
            $this.Bar.Detail = "$($Matches[1]) v$($Matches[2])"
            $this.Bar.Status = "checking"
            $this.UpdateCompileProgress()
            return
        }
        if ($Line -match "Fresh\s+(\S+)\s+v") {
            $this.CratesCompiled++
            $this.UpdateCompileProgress()
            return
        }
        if ($Line -match "Documenting\s+(\S+)") {
            $this.Phase = "compile"
            $this.CratesCompiled++
            $this.Bar.Detail = "doc $($Matches[1])"
            $this.Bar.Status = "documenting"
            $this.UpdateCompileProgress()
            return
        }
        if ($Line -match "Linking") {
            $this.Phase = "link"
            $this.Bar.Detail = "linking"
            $this.Bar.Status = "linking"
            $this.Bar.Current = [math]::Round($this.Bar.Total * 0.95)
            return
        }
        if ($Line -match "Running\s+") {
            $this.Phase = "test"
            $this.Bar.Detail = "running"
            $this.Bar.Status = "testing"
            $this.Bar.Current = [math]::Round($this.Bar.Total * 0.90)
            return
        }
        if ($Line -match "Finished") {
            $this.Phase = "done"
            $this.Bar.Status = "done"
            $this.Bar.Current = $this.Bar.Total
            $this.Bar.Detail = "done"
            return
        }
        if ($Line -match "^error")   { $null = $this.Errors.Add($Line) }
        if ($Line -match "^warning") { $null = $this.Warnings.Add($Line) }
    }

    [void] UpdateCompileProgress() {
        if ($this.CratesCompiled -gt $this.ExpectedCrates -and $this.ExpectedCrates -gt 0) {
            $this.Bar.Total = $this.CratesCompiled + 2
        }
        if ($this.ExpectedCrates -eq 0) {
            $this.Bar.Total = [math]::Max($this.Bar.Total, $this.CratesCompiled + 5)
        }
        $this.Bar.Current = $this.CratesCompiled
    }

    [void] DrawBar() {
        if (-not $this.DrawProgress) { return }
        $line = $this.Bar.RenderLine()
        Write-Host -NoNewline "`r$([char]0x1b)[2K$line"
    }

    [void] Finish([bool]$Success) {
        if (-not $this.DrawProgress) { return }
        $e = [char]0x1b
        Write-Host -NoNewline "`r$e[2K"

        $elapsed = $this.Bar.Timer.Elapsed
        $timeStr = ($elapsed.TotalSeconds -lt 60) ?
            "$([math]::Round($elapsed.TotalSeconds, 1))s" :
            "$([math]::Floor($elapsed.TotalMinutes))m$([math]::Round($elapsed.TotalSeconds % 60, 1))s"

        $errCount = $this.Errors.Count
        $warnCount = $this.Warnings.Count
        $warnStr = ($warnCount -gt 0) ? " $e[33m${warnCount}warn$e[0m" : ""
        $crateStr = "$e[2m($($this.CratesCompiled) crates)$e[0m"

        if ($Success) {
            Write-Host "  $e[32mok$e[0m $($this.Bar.Label)$warnStr $crateStr $e[2m$timeStr$e[0m"
        } else {
            Write-Host "  $e[31mFAIL$e[0m $($this.Bar.Label) ${errCount} error(s)$warnStr $crateStr $e[2m$timeStr$e[0m"
        }
    }
}

function Invoke-CargoWithProgress {
    param(
        [string]$Label = "cargo",
        [string[]]$CargoArgs,
        [bool]$ShowProgress = $true,
        [bool]$ShowOutput = $false
    )

    $streamer = [CargoStreamer]::new($Label, $ShowProgress)

    $cargoPath = (Get-Command cargo -ErrorAction SilentlyContinue).Source
    if (-not $cargoPath) { $cargoPath = "cargo" }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $cargoPath
    $psi.Arguments = $CargoArgs -join " "
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.WorkingDirectory = (Get-Location).Path

    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi

    $script:ActiveCargoProcess = $proc

    if ($ShowProgress) { Hide-Cursor }

    $cancelled = $false

    try {
        $null = $proc.Start()

        $stdoutReader = $proc.StandardOutput
        $stderrReader = $proc.StandardError
        $stdoutTask = $stdoutReader.ReadLineAsync()
        $stderrTask = $stderrReader.ReadLineAsync()
        $stdoutDone = $false
        $stderrDone = $false
        $lastRedraw = [System.Diagnostics.Stopwatch]::StartNew()

        $streamer.Bar.Detail = "waiting for cargo..."
        $streamer.DrawBar()

        while (-not $stdoutDone -or -not $stderrDone) {
            $activity = $false

            if (-not $stderrDone -and $stderrTask.IsCompleted) {
                $line = $stderrTask.Result
                if ($null -eq $line) {
                    $stderrDone = $true
                } else {
                    $streamer.ParseLine($line, "stderr")
                    $activity = $true
                    $stderrTask = $stderrReader.ReadLineAsync()
                }
            }

            if (-not $stdoutDone -and $stdoutTask.IsCompleted) {
                $line = $stdoutTask.Result
                if ($null -eq $line) {
                    $stdoutDone = $true
                } else {
                    $streamer.ParseLine($line, "stdout")
                    $activity = $true
                    $stdoutTask = $stdoutReader.ReadLineAsync()
                }
            }

            if ($ShowProgress -and $lastRedraw.ElapsedMilliseconds -gt 66) {
                $streamer.DrawBar()
                $lastRedraw.Restart()
            }
            if (-not $activity) {
                Start-Sleep -Milliseconds 16
            }
        }
        $proc.WaitForExit()
    }
    catch {
        $cancelled = $true
        Kill-CargoTree $proc
    }
    finally {
        if ($ShowProgress) { Show-Cursor }
        if (-not $proc.HasExited) {
            Kill-CargoTree $proc
        }
        $script:ActiveCargoProcess = $null
    }

    if ($cancelled) {
        Write-Host -NoNewline "`r$([char]0x1b)[2K"
        Write-Host "  cancelled" -ForegroundColor Yellow
        return [PSCustomObject]@{
            ExitCode = 130
            Output   = [string[]]$streamer.AllOutput.ToArray()
            StdoutLines = [string[]]$streamer.StdoutLines.ToArray()
            StderrLines = [string[]]$streamer.StderrLines.ToArray()
            Errors   = [string[]]@("cancelled by user")
            Warnings = [string[]]$streamer.Warnings.ToArray()
            Elapsed  = $streamer.Bar.Timer.Elapsed
            Success  = $false
        }
    }

    $exitCode = $proc.ExitCode
    $success = $exitCode -eq 0
    $streamer.Finish($success)
    $proc.Dispose()

    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output   = [string[]]$streamer.AllOutput.ToArray()
        StdoutLines = [string[]]$streamer.StdoutLines.ToArray()
        StderrLines = [string[]]$streamer.StderrLines.ToArray()
        Errors   = [string[]]$streamer.Errors.ToArray()
        Warnings = [string[]]$streamer.Warnings.ToArray()
        Elapsed  = $streamer.Bar.Timer.Elapsed
        Success  = $success
    }
}

function Kill-CargoTree {
    param([System.Diagnostics.Process]$Proc)
    if ($null -eq $Proc) { return }
    try {
        if ($Proc.HasExited) { return }
        $procId = $Proc.Id
        $null = taskkill /F /T /PID $procId 2>&1
        $deadline = [System.Diagnostics.Stopwatch]::StartNew()
        while (-not $Proc.HasExited -and $deadline.ElapsedMilliseconds -lt 3000) {
            Start-Sleep -Milliseconds 50
        }
        if (-not $Proc.HasExited) {
            $Proc.Kill($true)
        }
    } catch {}
}

function Get-ExpectedCrateCount {
    $lockPath = Join-Path (Get-Location) "Cargo.lock"
    if (-not (Test-Path $lockPath)) { return 0 }
    $content = Get-Content $lockPath -Raw
    $count = ([regex]::Matches(
        $content, '^\[\[package\]\]',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )).Count
    return $count
}