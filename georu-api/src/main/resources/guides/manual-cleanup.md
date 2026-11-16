# 수작업 정리 안내서

이관 제품은 타겟 데이터를 **지우지 않습니다** (TRUNCATE·DELETE 미실행). 기존 데이터 때문에 적재가 중단되면, 이 안내서에 따라 담당자가 직접 정리한 뒤 다시 실행합니다.

> 정리는 되돌릴 수 없습니다. 반드시 백업을 먼저 하고, 운영 DB 는 DBA 와 함께 진행하세요.

## 언제 이 안내서를 보나

- 실행 화면이나 텔레그램에 **"[기존 데이터] 타겟에 기존 데이터가 있어 적재를 중단했습니다"** 가 나왔을 때
- 이관이 중간에 실패해서 일부만 적재된 테이블을 처음부터 다시 넣어야 할 때
- WRITER_TEST(샘플 실행)로 넣은 데이터를 지우고 NORMAL 로 이관할 때

## 먼저 판단할 것: 정리할지, 그대로 둘지

| 상황 | 조치 |
|---|---|
| 적재 쿼리가 `INSERT ... ON DUPLICATE KEY UPDATE` (UPSERT) 또는 `INSERT IGNORE` | 정리하지 않고 `ignoreExistingData=true` 로 다시 실행해도 됨 |
| 적재 쿼리가 일반 `INSERT` | 정리 후 재실행 (그대로 실행하면 중복키 오류 또는 중복 데이터) |
| 기존 데이터가 샘플 실행(WRITER_TEST) 적재분뿐 | 등록자 `MIG-TEST` 행만 정리 후 재실행 |
| 기존 데이터가 운영 중 생긴 실제 데이터 | 정리하지 말고 담당자와 이관 범위를 다시 정함 |

경고 화면의 **"마지막 샘플 실행"** 값이 기존 건수와 같다면 샘플 실행 적재분일 가능성이 높습니다.

## 정리 순서

1. **대상 확인** - 경고 화면(또는 텔레그램)의 테이블 목록과 기존 건수를 기록합니다.
2. **백업** - 대상 테이블을 백업합니다.
   - MySQL: `CREATE TABLE 테이블명_bak_YYYYMMDD AS SELECT * FROM 테이블명;` 또는 `mysqldump`
   - Oracle: `CREATE TABLE 테이블명_BAK_YYYYMMDD AS SELECT * FROM 테이블명;` 또는 Data Pump
3. **FK 확인** - 이 테이블을 참조하는 자식 테이블이 있는지 봅니다. 자식이 있으면 **자식 테이블부터** 정리합니다.
   - MySQL: `SELECT TABLE_NAME, CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_NAME = '테이블명';`
   - Oracle: `SELECT TABLE_NAME, CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE R_CONSTRAINT_NAME IN (SELECT CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE TABLE_NAME = '테이블명' AND CONSTRAINT_TYPE IN ('P','U'));`
4. **정리 실행** - 범위에 맞는 방법을 고릅니다.
   - 전체: `TRUNCATE TABLE 테이블명;` (FK 로 참조되면 실패할 수 있음 → 자식부터 정리하거나 `DELETE`)
   - 샘플 실행분만: `DELETE FROM 테이블명 WHERE INUSERID = 'MIG-TEST';` (감사 컬럼명은 테이블마다 확인)
   - 기간 조건으로 이관한 범위만: `DELETE FROM 테이블명 WHERE 기간컬럼 BETWEEN '시작' AND '종료';`
5. **건수 확인** - `SELECT COUNT(*) FROM 테이블명;` 이 기대한 값(보통 0)인지 확인합니다.
6. **재실행** - 이관 화면에서 같은 조건으로 다시 실행합니다. 실행 전 확인이 다시 기존 데이터를 검사합니다.

## 여러 테이블을 정리할 때

- 부모 → 자식 순서로 **적재**했다면, 정리는 **자식 → 부모** 순서입니다.
- 시나리오(도메인) 단위로 다시 넣을 때는 시나리오의 Phase 역순으로 정리합니다.

## 주의사항

- `FOREIGN_KEY_CHECKS=0` 같은 FK 검사 해제는 데이터 정합성을 깨뜨릴 수 있어 권장하지 않습니다. 꼭 필요하면 같은 세션에서 다시 켜고, DBA 와 함께 합니다.
- `TRUNCATE` 는 대부분의 DB 에서 롤백할 수 없습니다. 백업 없이 실행하지 마세요.
- 정리 후 재실행하면 완료 판정이 "적재 전 0 → 적재 후 N" 으로 건수를 다시 비교합니다. 차이가 있으면 완료(경고)로 표시됩니다.
