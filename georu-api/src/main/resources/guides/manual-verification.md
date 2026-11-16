# 수작업 확인 가이드

제품이 모든 DB 에서 같은 방식으로 자동 판정하지 못하는 부분을 담당자가 직접 확인하는 방법입니다.

## 1. 완료(경고) - 건수가 맞지 않을 때

완료 판정은 모든 DB 에서 같은 기준을 씁니다.

- **처리 건수** = Writer 에 넘긴 건수 - Writer 실패 (DB 가 돌려주는 건수는 쓰지 않음)
- **기대 건수** = 소스 건수 - Processor 필터 건수
- 차이가 0 이 아니면 완료(경고)

### "타겟 증가 건수가 처리 건수와 다름" 경고

| 적재 쿼리 | 의미 | 확인 방법 |
|---|---|---|
| `INSERT IGNORE` | 중복키 행을 DB 가 조용히 건너뜀 | 증가 건수 + 건너뛴 건수 = 처리 건수인지 확인. 건너뛴 행은 소스에서 PK 중복을 찾아 봅니다 |
| `ON DUPLICATE KEY UPDATE` / `MERGE` | 이미 있는 행은 갱신(건수 증가 없음) | 갱신 대상이었던 행 수 = 처리 건수 - 증가 건수 인지 확인 |
| 일반 `INSERT` | 정상이라면 증가 건수 = 처리 건수 | 다르면 다른 작업이 동시에 타겟을 바꿨는지, 트리거가 있는지 확인 |

PK 중복 확인 예 (소스 쿼리에서):

```
SELECT PK컬럼, COUNT(*) FROM (소스 SELECT) GROUP BY PK컬럼 HAVING COUNT(*) > 1;
```

### "소스 건수와 실제로 읽은 건수가 다름" 경고

- 페이징 정렬 기준(`ORDER BY`)이 유일하지 않으면 페이지 경계에서 행이 빠지거나 중복됩니다. 정렬키에 PK 를 추가하세요.
- 이관 중에 소스 데이터가 바뀌었는지 확인하세요.

### 1:N 분할, N:1 병합 테이블

소스 1건이 타겟 여러 건이 되거나 그 반대인 테이블은 건수 비교가 의미 없습니다. Strategy 에서 건수 비교를 끕니다.

```
@Override
protected TableOptions getTableOptions(String tableName) {
    return switch (tableName) {
        case "tb_user_hist" -> TableOptions.builder().countCheck(TableOptions.CountCheck.SKIP).build();
        default -> TableOptions.DEFAULT;
    };
}
```

## 2. 실행 전 점검 - "판단 불가" 가 나올 때

타겟 DB 에 맞는 규칙(DialectRule)이 없으면 길이·날짜 범위를 자동으로 판단하지 못합니다. 규칙이 있는 DB: MySQL/MariaDB, Oracle, SQL Server.

### 길이 판단 불가

1. 타겟 컬럼 정의를 확인합니다 (예: `VARCHAR(50)` 이 문자 50자인지, 50바이트인지 DB 문서 확인).
2. 실행 전 점검 결과의 샘플 값 중 가장 긴 값을 그 기준으로 셉니다. 한글은 UTF-8 에서 1글자 3바이트입니다.
3. 의심되면 WRITER_TEST 로 몇 건 넣어 보고 오류가 나는지 확인합니다.

### 날짜 범위 판단 불가

DB 문서에서 날짜 타입의 허용 범위를 확인하고, 소스에 `0000-00-00`, `9999-12-31`, 1900년 이전 같은 값이 있는지 조회합니다.

### 규칙 추가

같은 DB 를 계속 쓴다면 DialectRule 을 추가하는 것이 낫습니다. 절차는 개발자 가이드 `document/docs/developer-guides/DialectRule_추가_가이드.md` 를 보세요.

## 3. 조회 불가 (권한 또는 테이블 없음)

메타 정보는 SELECT 권한만 있으면 읽을 수 있습니다. "조회 불가"가 나오면:

- 테이블명·스키마(소유자)가 맞는지 확인합니다.
- 다른 스키마의 테이블을 시노님으로 쓰는 경우, 시노님이 실제로 있는지 확인합니다 (Oracle 은 자동으로 시노님을 따라갑니다).
- 이관 계정에 그 테이블의 권한이 있는지 DBA 에게 확인합니다. 적재에는 INSERT 권한, UPSERT 쿼리에는 UPDATE 권한도 필요합니다.
