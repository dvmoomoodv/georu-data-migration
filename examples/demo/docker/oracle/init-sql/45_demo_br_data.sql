-- 지사 DB(DEMO_BR) 데이터 / 손으로 씀. 건수: BR_BRANCH 10, BR_MEMBER 300 (탈퇴 20 → 이관 280), BR_MEMBER_POINT 750
-- DEMO_BR 계정으로 실행 (01_init.sh 의 *_demo_br_*.sql)

INSERT INTO "BR_BRANCH" ("BRANCH_CD", "BRANCH_NM", "REGION", "USE_YN")
SELECT 'B' || LPAD(LEVEL, 2, '0'),
       DECODE(LEVEL, 1, '강남점', 2, '종로점', 3, '부산점', 4, '대구점', 5, '광주점', 6, '대전점', 7, '수원점', 8, '인천점', 9, '울산점', '폐점'),
       DECODE(LEVEL, 1, '서울', 2, '서울', 3, '부산', 4, '대구', 5, '광주', 6, '대전', 7, '경기', 8, '인천', 9, '울산', '-'),
       CASE WHEN LEVEL = 10 THEN 'N' ELSE 'Y' END
  FROM DUAL CONNECT BY LEVEL <= 10;

INSERT INTO "BR_MEMBER" ("MEMBER_ID", "LOGIN_ID", "MEMBER_NM", "EMAIL", "PHONE", "BRANCH_CD", "GRADE_CD", "STATUS", "JOIN_DT")
SELECT LEVEL,
       'br' || LPAD(LEVEL, 4, '0'),
       '지사회원' || LEVEL,
       'br' || LEVEL || '@demo-br.local',
       '010-7' || LPAD(MOD(LEVEL, 1000), 3, '0') || '-' || LPAD(MOD(LEVEL * 37, 10000), 4, '0'),
       'B' || LPAD(MOD(LEVEL - 1, 9) + 1, 2, '0'),
       DECODE(MOD(LEVEL, 5), 0, 'BRONZE', 1, 'SILVER', 2, 'GOLD', 3, 'PLATINUM', 'DIAMOND'),
       CASE WHEN MOD(LEVEL, 15) = 0 THEN 'D' ELSE 'A' END,
       DATE '2025-01-01' + LEVEL
  FROM DUAL CONNECT BY LEVEL <= 300;

-- 본사 회원(DEMO.MEM_MEMBER 1~1000) 중 4의 배수가 아닌 회원만 포인트가 있다
INSERT INTO "BR_MEMBER_POINT" ("MEMBER_ID", "BRANCH_CD", "POINT_BAL", "UPD_DT")
SELECT n, 'B' || LPAD(MOD(n, 9) + 1, 2, '0'), n * 10, DATE '2026-01-01' + MOD(n, 300)
  FROM (SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 1000)
 WHERE MOD(n, 4) <> 0;

COMMIT;
EXIT
