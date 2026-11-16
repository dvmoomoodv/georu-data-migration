# 📚 JVM 옵션 참고 가이드

## 🎯 GC 종류별 특징

| GC 종류 | 특징 | 적합한 상황 | 옵션 |
|---------|------|-------------|------|
| **G1GC** | 균형잡힌 성능 | 일반적인 서버 애플리케이션 | `-XX:+UseG1GC` |
| **ZGC** | 저지연 (10ms 이하) | 대용량 힙, 지연에 민감 | `-XX:+UseZGC` |
| **Shenandoah** | 저지연 | ZGC 대안 | `-XX:+UseShenandoahGC` |
| **ParallelGC** | 높은 처리량 | 배치 작업, 지연 덜 중요 | `-XX:+UseParallelGC` |

---

## ⚙️ GC별 권장 설정

### G1GC (Java 11+ 기본)
```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200          # 목표 GC 일시정지 시간
-XX:G1HeapRegionSize=16m          # 힙 리전 크기
-XX:InitiatingHeapOccupancyPercent=45  # GC 시작 임계값
```

### ZGC (Java 15+, 대용량 힙 권장)
```bash
-XX:+UnlockExperimentalVMOptions
-XX:+UseZGC
-XX:ZCollectionInterval=0         # 자동 GC 간격 (0=비활성화)
-XX:SoftMaxHeapSize=14g           # 소프트 힙 제한
```

### ParallelGC (배치 처리 최적)
```bash
-XX:+UseParallelGC
-XX:ParallelGCThreads=8           # 병렬 GC 스레드 수
-XX:MaxGCPauseMillis=500          # 허용 GC 일시정지 시간
```

---

## 💾 메모리 설정

### 힙 메모리
```bash
-Xms16g                           # 초기 힙 크기
-Xmx16g                           # 최대 힙 크기 (Xms와 동일 권장)
```

### 메타스페이스 (클래스 메타데이터)
```bash
-XX:MetaspaceSize=256m            # 초기 메타스페이스
-XX:MaxMetaspaceSize=512m         # 최대 메타스페이스
```

### Direct Memory (NIO 버퍼)
```bash
-XX:MaxDirectMemorySize=2g        # Direct 버퍼 최대 크기
```

---

## 🚨 OOM 처리

```bash
# OOM 발생 시 힙 덤프 생성
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=logs/heapdump.hprof

# OOM 발생 시 즉시 종료 (좀비 프로세스 방지)
-XX:+ExitOnOutOfMemoryError

# OOM 발생 시 스크립트 실행
-XX:OnOutOfMemoryError="./scripts/oom-handler.sh %p"
```

---

## 📊 GC 로그 설정 (Java 11+)

### 기본 GC 로그
```bash
-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m
```

### 상세 GC 로그
```bash
-Xlog:gc*=debug:file=logs/gc-debug.log:time,uptime,level,tags
-Xlog:gc+phases=debug:file=logs/gc-phases.log:time,uptime
-Xlog:gc+heap=debug:file=logs/gc-heap.log:time,uptime
```

### 로그 형식 설명
```
-Xlog:gc*:file=<파일>:<데코레이터>:filecount=<개수>,filesize=<크기>
       │      │       │              │
       │      │       │              └─ 로테이션 설정
       │      │       └─ 타임스탬프 등 추가 정보
       │      └─ 출력 파일 경로
       └─ 로그 대상 (gc* = 모든 GC 관련)
```

---

## 🐛 디버깅 옵션

### 원격 디버깅
```bash
# Java 11+
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005

# suspend=y: 디버거 연결까지 대기
# suspend=n: 바로 시작
```

### JMX 원격 모니터링
```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.rmi.port=9010
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
```

---

## 🚫 System.gc() 관련

### System.gc() 호출 무시 (운영 환경 권장)
```bash
-XX:+DisableExplicitGC
```

### System.gc() 호출 시 Concurrent GC 사용
```bash
-XX:+ExplicitGCInvokesConcurrent
```

---

## 📈 성능 튜닝

### 컴파일러 최적화
```bash
-XX:+TieredCompilation             # 단계별 컴파일 (기본 활성화)
-XX:CompileThreshold=10000         # JIT 컴파일 임계값
```

### 스레드 스택 크기
```bash
-Xss512k                           # 스레드당 스택 크기
```

### Large Pages (Linux)
```bash
-XX:+UseLargePages
-XX:LargePageSizeInBytes=2m
```

---

## 🎯 시나리오별 권장 설정

### 대용량 배치 처리 (현재 프로젝트)
```bash
-Xms16g -Xmx16g
-XX:+UseZGC
-XX:+HeapDumpOnOutOfMemoryError
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m
```

### 메모리 제한 환경 (4GB 이하)
```bash
-Xms2g -Xmx4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
```

### 개발/테스트 환경
```bash
-Xms1g -Xmx2g
-XX:+UseG1GC
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

