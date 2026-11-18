# =============================================================================
# georu 데모 앱 컨테이너 이미지 - 멀티스테이지. 빌드 컨텍스트는 저장소 루트 (라이브러리 모듈을 함께 빌드)
#   docker build -f examples/demo/Dockerfile -t georu-demo:local .
#   docker compose 는 examples/demo/docker-compose.yml 이 context: ../.. 로 지정한다
# =============================================================================
# JDK 버전 고정 (CVE-2026-114938 대응). Temurin 17.0.21.1 이 나오면 두 FROM 의 태그를 그 버전으로 바꾸고
# 아래 ENTRYPOINT 의 -XX:-UseBASE64Intrinsics 를 지운다
FROM eclipse-temurin:17.0.21_9-jdk AS build
WORKDIR /workspace
COPY . .
RUN printf 'org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\norg.gradle.daemon=false\norg.gradle.parallel=true\norg.gradle.caching=true\n' > gradle.properties \
    && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -p examples/demo bootJar -x test \
    && JAR=$(ls examples/demo/build/libs/*.jar | grep -v plain | head -1) \
    && java -Djarmode=layertools -jar "$JAR" extract --destination extracted

FROM eclipse-temurin:17.0.21_9-jre
ENV TZ=Asia/Seoul \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -d /app app \
    && mkdir -p /app/logs /app/data && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER app
EXPOSE 8081
VOLUME ["/app/logs", "/app/data"]
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+UseZGC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul \
 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof -XX:+ExitOnOutOfMemoryError \
 -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -fs http://localhost:${SERVER_PORT:-8081}/actuator/health/live || exit 1
# -XX:-UseBASE64Intrinsics: CVE-2026-114938 임시 완화. docker-compose 가 JAVA_OPTS 를 덮어써도 빠지지 않도록 여기에 둔다
ENTRYPOINT ["sh", "-c", "exec java -XX:-UseBASE64Intrinsics $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
