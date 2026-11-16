@echo off
chcp 65001 > nul
setlocal EnableDelayedExpansion

REM ============================================================================
REM georu Local Execution Script (Batch)
REM ============================================================================
REM Usage: run-scripts\run-local.bat [profile] [heap] [logLevel]
REM        run-scripts\run-local.bat dev
REM        run-scripts\run-local.bat local 8g
REM        run-scripts\run-local.bat local 16g DEBUG
REM ============================================================================

set PROFILE=%1
set HEAP_SIZE=%2
set LOG_LEVEL=%3

if "%PROFILE%"=="" set PROFILE=local
if "%HEAP_SIZE%"=="" set HEAP_SIZE=16g
if "%LOG_LEVEL%"=="" set LOG_LEVEL=INFO

echo ============================================
echo georu Application Launcher
echo ============================================

REM Change to project root
cd /d "%~dp0.."

REM Find JAR file
set JAR_FILE=
for /f "delims=" %%i in ('dir /b /o-d build\libs\*.jar 2^>nul ^| findstr /v "\-plain.jar"') do (
    if not defined JAR_FILE set JAR_FILE=build\libs\%%i
)

if not defined JAR_FILE (
    echo [ERROR] JAR file not found.
    echo [TIP] Run build first (repo root): gradlew :examples:demo:bootJar
    exit /b 1
)

echo [JAR] %JAR_FILE%
echo [Profile] %PROFILE%
echo [Heap] %HEAP_SIZE%
echo [LogLevel] %LOG_LEVEL%

REM Create logs directory
if not exist logs mkdir logs

REM JVM options
set JVM_OPTS=-Xms%HEAP_SIZE% -Xmx%HEAP_SIZE%
set JVM_OPTS=%JVM_OPTS% -XX:+UnlockExperimentalVMOptions -XX:+UseZGC
set JVM_OPTS=%JVM_OPTS% -Dfile.encoding=UTF-8
set JVM_OPTS=%JVM_OPTS% -XX:+HeapDumpOnOutOfMemoryError
set JVM_OPTS=%JVM_OPTS% -XX:HeapDumpPath=logs/heapdump.hprof
set JVM_OPTS=%JVM_OPTS% -XX:+ExitOnOutOfMemoryError
set JVM_OPTS=%JVM_OPTS% -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m
set JVM_OPTS=%JVM_OPTS% -Xlog:gc+phases=debug:file=logs/gc-phases.log:time,uptime:filecount=3,filesize=5m
set JVM_OPTS=%JVM_OPTS% -Dspring.profiles.active=%PROFILE%
set JVM_OPTS=%JVM_OPTS% -Dlogging.level.root=%LOG_LEVEL%

echo.
echo Starting application...
echo ============================================

java %JVM_OPTS% -jar %JAR_FILE%

exit /b %ERRORLEVEL%
