#!/bin/bash
# ============================================================================
# georu Local Execution Script (Linux/Mac)
# ============================================================================
# Usage: ./run-scripts/run-local.sh
#        ./run-scripts/run-local.sh dev
#        ./run-scripts/run-local.sh local 8g
#        ./run-scripts/run-local.sh local 16g DEBUG
# ============================================================================

PROFILE=${1:-local}
HEAP_SIZE=${2:-16g}
LOG_LEVEL=${3:-INFO}

echo "============================================"
echo "georu Application Launcher"
echo "============================================"

# Change to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

# Find JAR file
JAR_FILE=$(ls -t build/libs/*.jar 2>/dev/null | grep -v '\-plain.jar' | head -1)

if [ -z "$JAR_FILE" ] || [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] JAR file not found."
    echo "[TIP] Run build first (repo root): ./gradlew :examples:demo:bootJar"
    exit 1
fi

echo "[JAR] $JAR_FILE"
echo "[Profile] $PROFILE"
echo "[Heap] $HEAP_SIZE"
echo "[LogLevel] $LOG_LEVEL"

# Create logs directory
mkdir -p logs

# JVM options
JVM_OPTS=(
    # Memory settings
    "-Xms${HEAP_SIZE}"
    "-Xmx${HEAP_SIZE}"

    # ZGC settings
    "-XX:+UnlockExperimentalVMOptions"
    "-XX:+UseZGC"

    # Encoding
    "-Dfile.encoding=UTF-8"

    # OOM handling
    "-XX:+HeapDumpOnOutOfMemoryError"
    "-XX:HeapDumpPath=logs/heapdump.hprof"
    "-XX:+ExitOnOutOfMemoryError"

    # GC logs
    "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
    "-Xlog:gc+phases=debug:file=logs/gc-phases.log:time,uptime:filecount=3,filesize=5m"

    # Spring profile
    "-Dspring.profiles.active=${PROFILE}"

    # Log level
    "-Dlogging.level.root=${LOG_LEVEL}"
)

echo ""
echo "Starting application..."
echo "============================================"

exec java "${JVM_OPTS[@]}" -jar "$JAR_FILE"

