# Writer 고르기 (BATCH · REUSE)

테이블마다 Writer 의 기반 클래스를 둘 중 하나로 고릅니다. **기본은 BATCH** 이고, 대부분의 테이블은 그대로 둡니다.
코드 생성기는 정의 파일의 `Writer` 열(`BATCH` · `REUSE`) 또는 화면 설정의 기본값으로 정합니다.

| | BATCH (기본) | REUSE |
|---|---|---|
| 기반 클래스 | `AbstractBatchMigrationWriter` | `AbstractMigrationWriter` + `writeToDatabase(items)` |
| 보내는 방식 | 행을 모아 한 번에 보낸다 (묶음 실행) | 행마다 바로 실행한다 |
| 속도 | 빠르다. 대량 테이블에 맞다 | 행마다 DB 왕복이 있어 느리다 |
| 실패했을 때 | 묶음 전체가 실패하고, 로그에는 묶음 단위로 남는다 (`Batch insert failed for …`) | 실패한 행에서 멈추고, 로그에 그 행의 ID 가 남는다 (`Failed to write … item: <ID>`) |
| 롤백 | 그 쓰기 한 번(청크 또는 Queue 묶음)이 통째로 롤백 | 같음 |
| 재시도 | Queue Writer 의 일시 오류(교착 · 접속 끊김) 재시도는 둘 다 같다. 데이터 오류는 재시도하지 않는다 | 같음 |
| 어울리는 건수 | 수만 건 이상 | 수만 건 이하 (그 이상은 시간이 늘어난다) |
| 어울리는 데이터 | 형식이 고르고 행 오류가 드문 데이터 | 형식이 고르지 않거나 키 · 필수값 위반이 있을 수 있는 데이터 |
| 큰 행 (BLOB · 긴 본문) | 한 묶음이 커져 메모리 · 패킷 한도(MySQL `max_allowed_packet`)에 걸릴 수 있다 | 행마다 보내 한도 걱정이 적다 |
| 키를 돌려받는 INSERT | 값이 늦게 채워지거나 드라이버에 따라 안 채워진다 | 실행 직후 쓸 수 있다 |

## 업무 성격별 권장

같은 업체라도 테이블 성격에 따라 나뉩니다. 처음 정할 때 기준으로 쓰고, 테스트 모드 결과를 보고 바꿉니다.

| 업무 성격 | 예 | 건수 · 특징 | 권장 | 이유 |
|---|---|---|---|---|
| 이력 · 로그 | 접속 로그, 변경 이력, 발송 이력, 감사 로그 | 수십만 ~ 수천만. 단순 INSERT, 변환이 적다 | **BATCH** | 속도가 가장 중요하고 행 오류가 드물다 |
| 거래 · 실적 | 주문, 결제, 수강 신청, 포인트 적립 | 수만 ~ 수백만. 부모 키 참조, 금액 · 상태 코드 변환 | **BATCH** | 건수가 많다. 첫 적재에서 실패하면 그 테이블만 잠시 REUSE 로 원인 행을 찾는다 |
| 회원 · 고객 마스터 | 회원, 고객, 거래처 | 수천 ~ 수십만. 전화 · 이메일 형식, 중복 키 · 필수값 위반이 잦다. 자식 테이블이 참조 | 첫 적재 **REUSE** → 안정되면 **BATCH** | 어느 회원이 막혔는지 바로 보여야 하고, 이 키가 틀리면 자식 테이블이 모두 틀린다 |
| 조직 · 계층 | 회사, 부서, 사용자, 메뉴 | 수백 ~ 수천. 상위 코드를 참조(자기 참조), 넣는 순서가 중요 | **REUSE** | 작아서 속도 차이가 없고, 상위 누락 같은 행 단위 오류를 바로 본다 |
| 공통코드 · 설정 | 코드 그룹, 코드, 환경 설정 | 수십 ~ 수백 | **REUSE** | 속도 차이가 없다. 오류를 보기 쉬운 쪽 |
| 첨부 · 본문 | 첨부파일, 게시글 본문, 이미지 | 행이 크다 (BLOB · CLOB · 긴 텍스트) | **REUSE** (건수가 많으면 BATCH + 청크 줄이기) | 큰 행을 묶으면 메모리 · 패킷 한도에 걸릴 수 있다 |
| INSERT 뒤 키를 받아 쓰는 테이블 | 자동 증가 키를 자식에 넘겨야 하는 부모 | 키 생성이 DB 쪽 | **REUSE** | 행마다 키를 바로 받아야 한다 (채번을 georu 로 하면 해당 없음) |

## 이럴 때 REUSE

- **어느 행이 문제인지 찾아야 할 때**: 처음 적재해 보는 테이블, 원천 데이터 품질을 모르는 테이블, WRITER_TEST 에서 BATCH 가 실패했는데 원인 행을 모를 때
- **행마다 결과가 필요한 SQL**: INSERT 뒤 키를 돌려받는 SQL(`selectKey` · `useGeneratedKeys`)처럼 실행 직후 값이 필요할 때. BATCH 에서는 드라이버 · 설정에 따라 값이 늦게 채워지거나 채워지지 않는다
- **BATCH 에서만 드라이버 오류가 날 때**: 묶음 실행 설정(MySQL `rewriteBatchedStatements` 등)과 SQL 이 맞지 않는 경우
- **작은 테이블**: 코드표처럼 수백 건 이하면 속도 차이가 거의 없다. 오류를 보기 쉬운 쪽을 고른다

## 이럴 때 BATCH (기본)

- 수만 건 이상, 단순 INSERT · UPSERT
- 실행 전 점검(길이 · NOT NULL · 타입 · PK 중복)을 통과해 행 단위 오류가 드물 것으로 보는 테이블

## 권장 순서

1. 기본(BATCH)으로 생성해 READER_TEST → PROCESSOR_TEST → WRITER_TEST 를 돌린다.
2. WRITER_TEST 가 실패하고 로그로 원인 행을 못 찾으면 그 테이블만 REUSE 로 바꿔 다시 돌린다. 로그의 행 ID 로 원천 값을 고친다.
3. 고친 뒤에는 BATCH 로 되돌려도 되고, 건수가 적으면 REUSE 로 두어도 된다.

## 이미 생성한 Writer 를 바꾸기

Reader · Writer 파일을 만들지 않는 기본(ENGINE)으로 생성했다면 BATCH 테이블에는 Writer 파일이 없습니다 (엔진 공용 Writer 가 BATCH 로 씁니다).
그 테이블을 REUSE 로 바꾸려면 정의 파일의 `Writer` 열을 `REUSE` 로 바꿔 다시 생성하고 새로 생긴 Writer 파일과 업무 클래스의 `.writer(…)` 줄을 옮기거나,
아래처럼 Writer 를 직접 만들어 업무 클래스 선언에 `.writer(TbXxxWriter.class)` 를 더합니다.

다시 생성해도 `unzip -n` 은 이미 있는 파일을 덮지 않으므로 손으로 바꿉니다. `insertItem` 과 나머지는 그대로 둡니다.

```java
// BATCH → REUSE
public class TbXxxWriter extends AbstractMigrationWriter<TbXxxTargetDto> {      // AbstractBatchMigrationWriter → AbstractMigrationWriter

    @Override
    protected void writeItems(List<? extends TbXxxTargetDto> items) throws Exception {   // 이 메서드를 더한다
        writeToDatabase(items);
    }

    @Override
    protected void insertItem(SqlSession session, TbXxxTargetDto item) throws Exception {
        session.insert(NS + ".insertData", item);
    }
    ...
}
```

REUSE → BATCH 는 반대로 `extends AbstractBatchMigrationWriter` 로 바꾸고 `writeItems` 를 지웁니다.
