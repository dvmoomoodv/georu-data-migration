# 🚀 georu 실행 스크립트

JAR 파일 실행 시 JVM 옵션을 적용하기 위한 스크립트 모음입니다.

> ⚠️ `bootRunLocal` Gradle 태스크의 jvmArgs는 JAR 빌드 후에는 적용되지 않습니다.  
> 이 스크립트를 사용하면 동일한 JVM 옵션으로 JAR를 실행할 수 있습니다.

---

## 📁 파일 목록

| 파일 | 환경 | 설명 |
|------|------|------|
| `run-local.ps1` | Windows (PowerShell) | 권장 - 파라미터 지원 |
| `run-local.bat` | Windows (CMD) | 호환용 |
| `run-local.sh` | Linux / Mac | Bash 스크립트 |

---

## 🚀 사용법

### 1. 빌드 먼저 실행
```bash
.\gradlew :examples:demo:bootJar   (저장소 루트에서)
```

### 2. 스크립트로 실행

**PowerShell (권장)**
```powershell
# 기본 실행 (local 프로파일, 16GB 힙)
.\run-scripts\run-local.ps1

# 프로파일 지정
.\run-scripts\run-local.ps1 -Profile dev

# 힙 크기 변경
.\run-scripts\run-local.ps1 -HeapSize 8g

# 디버그 모드 (IDE에서 원격 디버깅 가능)
.\run-scripts\run-local.ps1 -Debug

# 조합 사용
.\run-scripts\run-local.ps1 -Profile dev -HeapSize 4g -Debug
```

**Batch (CMD)**
```batch
REM 기본 실행
run-scripts\run-local.bat

REM 프로파일 + 힙 크기 지정
run-scripts\run-local.bat dev 8g
```

**Bash (Linux/Mac)**
```bash
# 실행 권한 부여 (최초 1회)
chmod +x run-scripts/run-local.sh

# 기본 실행
./run-scripts/run-local.sh

# 프로파일 + 힙 크기 지정
./run-scripts/run-local.sh dev 8g
```

---

## ⚙️ 적용되는 JVM 옵션

### 메모리 설정
| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `-Xms` | 16g | 초기 힙 크기 |
| `-Xmx` | 16g | 최대 힙 크기 |

### GC 설정 (ZGC)
| 옵션 | 설명 |
|------|------|
| `-XX:+UnlockExperimentalVMOptions` | 실험적 옵션 활성화 |
| `-XX:+UseZGC` | ZGC 사용 (저지연 GC) |

### OOM 처리
| 옵션 | 설명 |
|------|------|
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 시 힙 덤프 생성 |
| `-XX:HeapDumpPath=logs/heapdump.hprof` | 힙 덤프 저장 경로 |
| `-XX:+ExitOnOutOfMemoryError` | OOM 시 즉시 종료 |

### GC 로그
| 옵션 | 설명 |
|------|------|
| `-Xlog:gc*:file=logs/gc.log:...` | GC 로그 파일 저장 |
| `filecount=5,filesize=10m` | 5개 파일, 각 10MB 로테이션 |

### 기타
| 옵션 | 설명 |
|------|------|
| `-Dfile.encoding=UTF-8` | 인코딩 설정 |
| `-Dspring.profiles.active=local` | Spring 프로파일 |

---

## 🐛 디버그 모드

`-Debug` 옵션 사용 시 원격 디버깅이 활성화됩니다.

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

### IntelliJ IDEA 연결 방법
1. `Run` → `Edit Configurations`
2. `+` → `Remote JVM Debug`
3. Host: `localhost`, Port: `5005`
4. `Debug` 버튼 클릭

---

## 📂 생성되는 로그 파일

```
logs/
├── gc.log              # GC 로그
├── gc-phases.log       # GC 단계별 상세 로그
└── heapdump.hprof      # OOM 발생 시 힙 덤프
```

---

## 🔧 커스터마이징

### 힙 크기 권장값

| 데이터 규모 | 권장 힙 | 예시 |
|-------------|---------|------|
| 소규모 (10만건 이하) | 2g ~ 4g | `-HeapSize 4g` |
| 중규모 (100만건) | 4g ~ 8g | `-HeapSize 8g` |
| 대규모 (1000만건) | 8g ~ 16g | `-HeapSize 16g` |
| 초대규모 (1억건+) | 16g ~ 32g | `-HeapSize 32g` |

### G1GC로 변경하려면

스크립트의 ZGC 옵션을 아래로 교체:
```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
```

