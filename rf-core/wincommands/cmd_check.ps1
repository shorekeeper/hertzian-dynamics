#Requires -Version 7.0
# cmd_check.ps1 [validation] [--features X,Y]
param([Parameter(ValueFromRemainingArguments)][string[]]$RawArgs)

Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

$features = ""
for ($i = 0; $i -lt $RawArgs.Count; $i++) {
    switch ($RawArgs[$i]) {
        "validation" { $features = "validation" }
        "--features" { $i++; if ($i -lt $RawArgs.Count) { $features = $RawArgs[$i] } }
        default      { if ($RawArgs[$i] -notmatch "^-") { $features = $RawArgs[$i] } }
    }
}

$label = $features ? $features : "no features"
Write-CmdHeader "check" "[$label]"

$cargoArgs = @("check", "--lib", "--all-targets", "--message-format=json")
if ($features) { $cargoArgs += "--features"; $cargoArgs += $features }

$result = Invoke-CargoWithProgress -Label "check $label" -CargoArgs $cargoArgs -ShowProgress $true -ShowOutput $false
$parsed = Parse-CargoJsonOutput -Lines $result.StdoutLines

if (-not $result.Success) {
    Format-CompileErrors -Errors $parsed.Errors
    Format-WarningSummary -Warnings $parsed.Warnings
    $global:LASTEXITCODE = 1
    return
}

Format-WarningSummary -Warnings $parsed.Warnings