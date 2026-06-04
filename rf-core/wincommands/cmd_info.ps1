# cmd_info.ps1 [system|vulkan|project|deps|all]
param([string]$Section = "all")
Get-ChildItem (Join-Path $PSScriptRoot "_*.ps1") | ForEach-Object { . $_.FullName }

Write-CmdHeader "info" "[$Section]"

function Show-System {
    Write-Host "    System" -ForegroundColor White
    $os = [System.Environment]::OSVersion
    $cpu = (Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1).Name
    $ram = [math]::Round((Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue).TotalPhysicalMemory / 1GB, 1)
    $rust = (rustc --version 2>&1) -join ""
    $cargo = (cargo --version 2>&1) -join ""

    Write-Host "      OS:      $($os.VersionString)" -ForegroundColor Gray
    Write-Host "      CPU:     $cpu" -ForegroundColor Gray
    Write-Host "      RAM:     ${ram} GB" -ForegroundColor Gray
    Write-Host "      Rust:    $rust" -ForegroundColor Gray
    Write-Host "      Cargo:   $cargo" -ForegroundColor Gray
    Write-Host ""
}

function Show-Vulkan {
    Write-Host "    Vulkan" -ForegroundColor White
    $found = $false
    try {
        $info = vulkaninfo --summary 2>&1 | Out-String
        if ($info -match "deviceName|GPU") {
            $found = $true
            ($info -split "`n") | Where-Object { $_ -match "deviceName|apiVersion|driverVersion|deviceType|GPU" } | Select-Object -First 16 | ForEach-Object {
                Write-Host "      $($_.Trim())" -ForegroundColor Gray
            }
        }
    } catch {}
    if (-not $found) {
        Write-Host "      vulkaninfo unavailable (install LunarG Vulkan SDK)" -ForegroundColor Yellow
    }
    # Compiler tools rf-core build.rs depends on.
    $glslc = (Get-Command glslc -ErrorAction SilentlyContinue).Source
    $glslang = (Get-Command glslangValidator -ErrorAction SilentlyContinue).Source
    Write-Host "      glslc:           $($glslc ? $glslc : 'not found')" -ForegroundColor $($glslc ? "Gray" : "Yellow")
    Write-Host "      glslangValidator: $($glslang ? $glslang : 'not found')" -ForegroundColor $($glslang ? "Gray" : "Yellow")
    Write-Host ""
}

function Show-Project {
    Write-Host "    Project" -ForegroundColor White
    $stats = Get-RsFileStats
    Write-Host "      Source files:  $($stats.Files)" -ForegroundColor Gray
    Write-Host "      Total lines:   $($stats.Total)" -ForegroundColor Gray
    Write-Host "      Code lines:    $($stats.Code)" -ForegroundColor Gray
    Write-Host "      Doc lines:     $($stats.Doc) ($($stats.DocPct)%)" -ForegroundColor Gray

    $testCount = (Get-ChildItem -Path "tests" -Filter "*.rs" -ErrorAction SilentlyContinue).Count
    $exampleCount = (Get-ChildItem -Path "examples" -Filter "*.rs" -ErrorAction SilentlyContinue).Count
    $shaderCount = (Get-ChildItem -Path "shaders" -Filter "*.comp" -ErrorAction SilentlyContinue).Count
    Write-Host "      Tests:         $testCount" -ForegroundColor Gray
    Write-Host "      Examples:      $exampleCount" -ForegroundColor Gray
    Write-Host "      Shaders:       $shaderCount" -ForegroundColor Gray

    $unitTestCount = 0
    Get-ChildItem -Path "src" -Recurse -Filter "*.rs" -ErrorAction SilentlyContinue | ForEach-Object {
        $unitTestCount += ([regex]::Matches((Get-Content $_.FullName -Raw), '#\[test\]')).Count
    }
    Write-Host "      Unit tests:    $unitTestCount" -ForegroundColor Gray
    Write-Host ""
}

function Show-Deps {
    Write-Host "    Dependencies" -ForegroundColor White
    if (Test-Path "Cargo.lock") {
        $lockContent = Get-Content "Cargo.lock" -Raw
        $depCount = ([regex]::Matches($lockContent, '^\[\[package\]\]', [System.Text.RegularExpressions.RegexOptions]::Multiline)).Count
        Write-Host "      Total crates (Cargo.lock): $depCount" -ForegroundColor Gray
    }
    $output = cargo tree --depth 1 2>&1
    if ($LASTEXITCODE -eq 0) {
        $directDeps = @($output | Where-Object { "$_" -match "^[|+`-]" }).Count
        Write-Host "      Direct dependencies:       $directDeps" -ForegroundColor Gray
        $output | Where-Object { "$_" -match "^[|+`-]" } | ForEach-Object {
            Write-Host "        $_" -ForegroundColor DarkGray
        }
    }
    Write-Host ""
}

switch ($Section) {
    "system"  { Show-System }
    "vulkan"  { Show-Vulkan }
    "project" { Show-Project }
    "deps"    { Show-Deps }
    "all"     { Show-System; Show-Vulkan; Show-Project; Show-Deps }
    default   { Write-Host "    unknown: $Section (use system, vulkan, project, deps, all)" -ForegroundColor Red }
}