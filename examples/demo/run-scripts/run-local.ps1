#!/usr/bin/env pwsh
# ============================================================================
# georu Local Execution Script (PowerShell)
# ============================================================================
# Usage: .\run-scripts\run-local.ps1
#        .\run-scripts\run-local.ps1 -Profile dev
#        .\run-scripts\run-local.ps1 -JarPath "custom-path.jar"
# ============================================================================

param(
    [string]$Profile = "local",
    [string]$JarPath = "",
    [string]$HeapSize = "16g",
    [string]$LogLevel = "INFO",
    [string]$PackageLogLevel = "",
    [switch]$Debug,
    [switch]$Help
)

# Help
if ($Help) {
    Write-Host @"
georu Local Execution Script

Usage:
    .\run-scripts\run-local.ps1 [options]

Options:
    -Profile         Spring profile (default: local)
    -JarPath         JAR file path (default: auto-detect)
    -HeapSize        Heap memory size (default: 16g)
    -LogLevel        Root log level (default: INFO)
    -PackageLogLevel Package log level (e.g., com.georu=DEBUG)
    -Debug           Enable debug mode (port: 5005)
    -Help            Show help

Examples:
    .\run-scripts\run-local.ps1
    .\run-scripts\run-local.ps1 -Profile dev -HeapSize 8g
    .\run-scripts\run-local.ps1 -LogLevel DEBUG
    .\run-scripts\run-local.ps1 -PackageLogLevel "com.georu=TRACE"
    .\run-scripts\run-local.ps1 -Debug
"@
    exit 0
}

# Change to project root directory
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
Set-Location $ProjectRoot

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "georu Application Launcher" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# Find JAR file
if ([string]::IsNullOrEmpty($JarPath)) {
    $JarPath = Get-ChildItem -Path "build/libs" -Filter "*.jar" -Exclude "*-plain.jar" |
               Sort-Object LastWriteTime -Descending |
               Select-Object -First 1 -ExpandProperty FullName
}

if (-not (Test-Path $JarPath)) {
    Write-Host "[ERROR] JAR file not found: $JarPath" -ForegroundColor Red
    Write-Host "[TIP] Run build first (repo root): .\gradlew :examples:demo:bootJar" -ForegroundColor Yellow
    exit 1
}

Write-Host "[JAR] $JarPath" -ForegroundColor Green
Write-Host "[Profile] $Profile" -ForegroundColor Green
Write-Host "[Heap] $HeapSize" -ForegroundColor Green
Write-Host "[LogLevel] $LogLevel" -ForegroundColor Green
if ($PackageLogLevel) {
    Write-Host "[PackageLog] $PackageLogLevel" -ForegroundColor Green
}

# Create logs directory
if (-not (Test-Path "logs")) {
    New-Item -ItemType Directory -Path "logs" | Out-Null
}

# JVM options
$JvmArgs = @(
    # Memory settings
    "-Xms$HeapSize",
    "-Xmx$HeapSize",

    # ZGC settings
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseZGC",

    # Encoding
    "-Dfile.encoding=UTF-8",

    # OOM handling
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=logs/heapdump.hprof",
    "-XX:+ExitOnOutOfMemoryError",

    # GC logs
    "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m",
    "-Xlog:gc+phases=debug:file=logs/gc-phases.log:time,uptime:filecount=3,filesize=5m",

    # Spring profile
    "-Dspring.profiles.active=$Profile"
)

# Log level settings
$JvmArgs += "-Dlogging.level.root=$LogLevel"

# Package-specific log level (e.g., com.georu=DEBUG)
if ($PackageLogLevel) {
    $parts = $PackageLogLevel -split "="
    if ($parts.Length -eq 2) {
        $JvmArgs += "-Dlogging.level.$($parts[0])=$($parts[1])"
    }
}

# Debug mode
if ($Debug) {
    $JvmArgs += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    Write-Host "[Debug] Waiting for debugger on port 5005" -ForegroundColor Magenta
}

Write-Host ""
Write-Host "Starting application..." -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# Execute
$Process = Start-Process -FilePath "java" -ArgumentList ($JvmArgs + @("-jar", $JarPath)) -NoNewWindow -PassThru -Wait

exit $Process.ExitCode

