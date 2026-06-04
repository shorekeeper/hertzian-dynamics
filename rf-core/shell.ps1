#Requires -Version 7.0
# shell.ps1 - Hertzian Dynamics interactive command shell.
#
# Usage:
#   .\shell.ps1                # Interactive REPL
#   .\shell.ps1 build full     # Single command mode
#   .\shell.ps1 test dsp_fft
#
# The shell auto-locates the rf-core crate by walking up from the
# script location and from the current working directory, looking
# for rf-core/Cargo.toml. All cargo invocations run with that as
# the working directory.

param(
    [Parameter(ValueFromRemainingArguments)]
    [string[]]$DirectArgs
)

$ErrorActionPreference = "Continue"
$global:HzStartTime = Get-Date
$global:CommandCount = 0
$global:ErrorCount = 0
$global:LastResult = $null

# Locate rf-core 

function Find-RfCoreRoot {
    $candidates = @(
        (Get-Location).Path,
        (Join-Path (Get-Location).Path "rf-core"),
        (Split-Path -Parent $PSScriptRoot),
        (Join-Path (Split-Path -Parent $PSScriptRoot) "rf-core")
    )
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $manifest = Join-Path $c "Cargo.toml"
        if (Test-Path $manifest) {
            $content = Get-Content $manifest -Raw -ErrorAction SilentlyContinue
            if ($content -match 'name\s*=\s*"rf-core"') {
                return (Resolve-Path $c).Path
            }
        }
    }
    return $null
}

$rfCoreRoot = Find-RfCoreRoot
if ($rfCoreRoot) {
    Set-Location $rfCoreRoot
} else {
    Write-Host "  rf-core not found near $($PSScriptRoot). Cargo commands will fail." -ForegroundColor Yellow
}

# Load command modules

$commandDir = Join-Path $PSScriptRoot "wincommands"
Get-ChildItem (Join-Path $commandDir "_*.ps1") -ErrorAction SilentlyContinue |
    ForEach-Object { . $_.FullName }

# Aliases

$global:Aliases = @{
    "b"  = "build"
    "t"  = "test"
    "c"  = "check"
    "r"  = "run"
    "s"  = "status"
    "i"  = "info"
    "h"  = "help"
    "q"  = "exit"
    "cl" = "clean"
    "ul" = "unlock"
    "!!" = "repeat"
}

# Helpers

function Resolve-CmdAlias {
    param([string]$Name)
    $clean = $Name.Trim().Trim([char]0)
    if ($global:Aliases.ContainsKey($clean)) { return $global:Aliases[$clean] }
    return $clean
}

function Parse-Input {
    param([string]$Line)
    $Line = $Line.Trim().Trim([char]0)
    if (-not $Line) { return $null }

    $tokens = @()
    $current = ""
    $inQuote = $false
    foreach ($ch in $Line.ToCharArray()) {
        if ($ch -eq [char]0) { continue }
        if ($ch -eq '"') { $inQuote = !$inQuote; continue }
        if ($ch -eq ' ' -and -not $inQuote -and $current) {
            $tokens += $current
            $current = ""
            continue
        }
        $current += $ch
    }
    if ($current) { $tokens += $current }
    if ($tokens.Count -eq 0) { return $null }

    $cmd = Resolve-CmdAlias $tokens[0].ToLower()
    $cmdArgs = if ($tokens.Count -gt 1) { $tokens[1..($tokens.Count - 1)] } else { @() }

    return @{ Command = $cmd; Args = $cmdArgs; Raw = $Line }
}

function Write-Prompt {
    $errTag = ($global:ErrorCount -gt 0) ? " $($global:ErrorCount)err" : ""
    $baseColor = ($global:ErrorCount -gt 0) ? "Red" : "Cyan"

    $gitInfo = ""
    $hasGit = $false
    try {
        $gitDir = git rev-parse --git-dir 2>$null
        if ($LASTEXITCODE -eq 0 -and $gitDir) { $hasGit = $true }
    } catch {}

    if ($hasGit) {
        $branch = git branch --show-current 2>$null
        if (-not $branch) {
            $branch = git rev-parse --short HEAD 2>$null
            if ($branch) { $branch = ":$branch" }
        }
        $commit = git rev-parse --short HEAD 2>$null

        $staged = 0; $unstaged = 0; $untracked = 0
        $statusLines = @(git status --porcelain 2>$null)
        foreach ($sl in $statusLines) {
            if ($sl.Length -lt 2) { continue }
            $idx = $sl[0]; $wt = $sl[1]
            if ($idx -ne ' ' -and $idx -ne '?') { $staged++ }
            if ($wt -ne ' ' -and $wt -ne '?') { $unstaged++ }
            if ($idx -eq '?' -and $wt -eq '?') { $untracked++ }
        }
        $dirty = ($staged + $unstaged + $untracked) -gt 0

        $ahead = 0; $behind = 0
        try {
            $ab = git rev-list --left-right --count "@{u}...HEAD" 2>$null
            if ($LASTEXITCODE -eq 0 -and $ab -match "(\d+)\s+(\d+)") {
                $behind = [int]$Matches[1]; $ahead = [int]$Matches[2]
            }
        } catch {}

        $branchColor = $dirty ? "Yellow" : "Green"
        Write-Host -NoNewline "hz" -ForegroundColor $baseColor
        Write-Host -NoNewline " " -ForegroundColor DarkGray
        Write-Host -NoNewline $branch -ForegroundColor $branchColor

        if ($commit) {
            Write-Host -NoNewline " $commit" -ForegroundColor DarkGray
        }
        if ($staged -gt 0)    { Write-Host -NoNewline " +$staged" -ForegroundColor Yellow }
        if ($unstaged -gt 0)  { Write-Host -NoNewline " ~$unstaged" -ForegroundColor Yellow }
        if ($untracked -gt 0) { Write-Host -NoNewline " ?$untracked" -ForegroundColor Yellow }
        if ($ahead -gt 0)     { Write-Host -NoNewline " ^$ahead" -ForegroundColor Green }
        if ($behind -gt 0)    { Write-Host -NoNewline " v$behind" -ForegroundColor Red }

        if (-not $dirty -and $ahead -eq 0 -and $behind -eq 0) {
            Write-Host -NoNewline " ok" -ForegroundColor Green
        }

        if ($errTag) { Write-Host -NoNewline $errTag -ForegroundColor Red }
        Write-Host -NoNewline "> " -ForegroundColor DarkGray
    } else {
        Write-Host -NoNewline "hz" -ForegroundColor $baseColor
        if ($errTag) { Write-Host -NoNewline $errTag -ForegroundColor Red }
        Write-Host -NoNewline "> " -ForegroundColor DarkGray
    }
}

function Invoke-Command-Script {
    param([string]$Command, [string[]]$CmdArgs)

    $Command = $Command.Trim().Trim([char]0)
    if (-not $Command) { return }

    $scriptPath = Join-Path $commandDir "cmd_$Command.ps1"
    if (-not (Test-Path $scriptPath)) {
        Write-Host "  unknown command: $Command" -ForegroundColor Red
        Write-Host "  type 'help' for available commands" -ForegroundColor DarkGray
        return
    }

    $global:CommandCount++
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $exitCode = 0

    try {
        & $scriptPath @CmdArgs
        if ($null -ne $LASTEXITCODE) { $exitCode = $LASTEXITCODE }
    } catch [System.Management.Automation.PipelineStoppedException] {
        if ($script:ActiveCargoProcess -and -not $script:ActiveCargoProcess.HasExited) {
            Kill-CargoTree $script:ActiveCargoProcess
            $script:ActiveCargoProcess = $null
        }
        Show-Cursor
        Write-Host "`n  interrupted" -ForegroundColor Yellow
        $exitCode = 130
    } catch {
        Write-Host "  command crashed: $($_.Exception.Message)" -ForegroundColor Red
        $exitCode = 1
    }

    $sw.Stop()

    if ($exitCode -ne 0) {
        $global:ErrorCount++
    }

    $global:LastResult = @{
        Command = $Command; Args = $CmdArgs; ExitCode = $exitCode
        Elapsed = $sw.Elapsed
    }
}

function Show-Banner {
    $title = "HERTZIAN DYNAMICS SHELL"
    $hint  = "rf-core dev console. type 'help', 'q' to quit"
    $inner = 50

    $tPad = $inner - $title.Length
    $tL = [math]::Floor($tPad / 2)
    $tR = $tPad - $tL
    $hPad = $inner - $hint.Length
    $hL = [math]::Floor($hPad / 2)
    $hR = $hPad - $hL

    Write-Host ""
    Write-Host "  $([char]0x2554)$("$([char]0x2550)" * $inner)$([char]0x2557)" -ForegroundColor DarkCyan
    Write-Host "  $([char]0x2551)$(" " * $tL)$title$(" " * $tR)$([char]0x2551)" -ForegroundColor Cyan
    Write-Host "  $([char]0x2551)$(" " * $hL)$hint$(" " * $hR)$([char]0x2551)" -ForegroundColor DarkGray
    Write-Host "  $([char]0x255A)$("$([char]0x2550)" * $inner)$([char]0x255D)" -ForegroundColor DarkCyan
    if ($rfCoreRoot) {
        Write-Host "  cwd: $rfCoreRoot" -ForegroundColor DarkGray
    }
    Write-Host ""
}

# Cleanup on exit

$null = Register-EngineEvent PowerShell.Exiting -Action {
    if ($script:ActiveCargoProcess -and -not $script:ActiveCargoProcess.HasExited) {
        Kill-CargoTree $script:ActiveCargoProcess
    }
}

# Main

if ($DirectArgs -and $DirectArgs.Count -gt 0) {
    $parsed = Parse-Input ($DirectArgs -join " ")
    if ($parsed) {
        Invoke-Command-Script $parsed.Command $parsed.Args
    }
    exit $global:LastResult.ExitCode
}

Show-Banner
$lastLine = ""

while ($true) {
    Write-Prompt

    $line = $null
    try {
        $line = Read-Host
    } catch {
        Write-Host ""
        continue
    }
    if (-not $line) { continue }
    $line = $line.Trim()
    if (-not $line) { continue }

    $parsed = Parse-Input $line
    if (-not $parsed) { continue }

    switch ($parsed.Command) {
        "exit" {
            $elapsed = (Get-Date) - $global:HzStartTime
            Write-Host "  session: $($global:CommandCount) commands, $($global:ErrorCount) errors, $([math]::Round($elapsed.TotalMinutes, 1))m" -ForegroundColor DarkGray
            Write-Host ""
            exit 0
        }
        "repeat" {
            if ($lastLine) {
                Write-Host "  repeating: $lastLine" -ForegroundColor DarkGray
                $parsed = Parse-Input $lastLine
                if ($parsed) {
                    Invoke-Command-Script $parsed.Command $parsed.Args
                }
            } else {
                Write-Host "  nothing to repeat" -ForegroundColor Yellow
            }
            continue
        }
        default {
            $lastLine = $line
            Invoke-Command-Script $parsed.Command $parsed.Args
        }
    }
    Write-Host ""
}