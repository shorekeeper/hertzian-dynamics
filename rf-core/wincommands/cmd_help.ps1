# cmd_help.ps1 [command]
param([string]$Topic = "")
Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

$commands = @(
    @{ Cmd = "build";  Alias = "b";  Desc = "Compile the library";
       Usage = "build [validation] [--release] [--features X,Y]" },
    @{ Cmd = "check";  Alias = "c";  Desc = "Type check all targets";
       Usage = "check [validation] [--features X,Y]" },
    @{ Cmd = "test";   Alias = "t";  Desc = "Run integration tests under tests/*.rs";
       Usage = "test [<name>|all] [--features X] [--filter X] [--no-gpu]" },
    @{ Cmd = "run";    Alias = "r";  Desc = "Run examples under examples/*.rs";
       Usage = "run [<name>|all] [--features X] [--release] [--no-gpu]" },
    @{ Cmd = "info";   Alias = "i";  Desc = "System, Vulkan, and project info";
       Usage = "info [system|vulkan|project|deps|all]" },
    @{ Cmd = "status"; Alias = "s";  Desc = "Git and build status";
       Usage = "status" },
    @{ Cmd = "clean";  Alias = "cl"; Desc = "Clean build artifacts or example dumps";
       Usage = "clean [target|out|all]" },
    @{ Cmd = "unlock"; Alias = "ul"; Desc = "Kill stuck cargo/rustc and release locks";
       Usage = "unlock" },
    @{ Cmd = "help";   Alias = "h";  Desc = "This help";
       Usage = "help [command]" }
)

if ($Topic) {
    $found = $commands | Where-Object { $_.Cmd -eq $Topic -or $_.Alias -eq $Topic }
    if ($found) {
        Write-Host ""
        Write-Host "  $($found.Cmd)" -ForegroundColor Cyan -NoNewline
        Write-Host " ($($found.Alias))" -ForegroundColor DarkGray -NoNewline
        Write-Host " - $($found.Desc)" -ForegroundColor White
        Write-Host ""
        Write-Host "  usage: $($found.Usage)" -ForegroundColor Gray
        Write-Host ""
    } else {
        Write-Host "  unknown command: $Topic" -ForegroundColor Red
    }
    return
}

Write-Host ""
Write-Host "  Commands:" -ForegroundColor White
Write-Host ""

foreach ($c in $commands) {
    $alias = "($($c.Alias))".PadRight(5)
    Write-Host "    " -NoNewline
    Write-Host "$($c.Cmd.PadRight(8))" -NoNewline -ForegroundColor Cyan
    Write-Host " $alias " -NoNewline -ForegroundColor DarkGray
    Write-Host "$($c.Desc)" -ForegroundColor Gray
}

Write-Host ""
Write-Host "  Shortcuts:" -ForegroundColor White
Write-Host "    !!       repeat last command" -ForegroundColor Gray
Write-Host "    q        quit" -ForegroundColor Gray
Write-Host ""
Write-Host "  Examples:" -ForegroundColor White
Write-Host "    test all                  run every integration test" -ForegroundColor DarkGray
Write-Host "    test dsp_fft              run only tests/dsp_fft.rs" -ForegroundColor DarkGray
Write-Host "    test all --no-gpu         skip gpu_tests feature" -ForegroundColor DarkGray
Write-Host "    run all                   run every example" -ForegroundColor DarkGray
Write-Host "    run am_roundtrip          run one example, stream output" -ForegroundColor DarkGray
Write-Host "    build validation          build with the validation feature" -ForegroundColor DarkGray
Write-Host ""