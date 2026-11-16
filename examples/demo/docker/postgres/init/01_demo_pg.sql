-- 여러 DB 예제 (demo.xdb) 소스: PostgreSQL 쇼핑몰 DB demo_pg (source-2) / 손으로 씀
-- 컨테이너 최초 기동 때 docker-entrypoint-initdb.d 가 POSTGRES_DB(demo_pg) 에서 POSTGRES_USER(demo) 로 실행한다
CREATE TABLE IF NOT EXISTS shop_customer (
      customer_id  integer      PRIMARY KEY
    , login_id     varchar(50)  NOT NULL
    , customer_nm  varchar(50)  NOT NULL
    , grade        varchar(10)  NOT NULL          -- GOLD / SILVER / BRONZE  (TOBE 코드 G / S / B)
    , joined_at    timestamp    NOT NULL
    , use_yn       char(1)      NOT NULL          -- N = 탈퇴 (이관 제외)
);
INSERT INTO shop_customer
SELECT g, 'shop' || lpad(g::text, 4, '0'), '고객' || g,
       (ARRAY['GOLD', 'SILVER', 'BRONZE'])[1 + g % 3],
       timestamp '2024-01-01 09:00:00' + make_interval(hours => g),
       CASE WHEN g % 25 = 0 THEN 'N' ELSE 'Y' END
  FROM generate_series(1, 500) g
ON CONFLICT DO NOTHING;                           -- 500건, 탈퇴 20 → 이관 480

CREATE TABLE IF NOT EXISTS shop_order (
      order_id     bigint         PRIMARY KEY
    , customer_id  integer        NOT NULL REFERENCES shop_customer
    , order_amt    numeric(12,0)  NOT NULL
    , ordered_at   timestamp      NOT NULL
    , status       varchar(10)    NOT NULL        -- PAID / SHIPPED / DONE / CANCEL
);
INSERT INTO shop_order
SELECT 100000 + g, 1 + (g * 7) % 500, 1000 * (1 + g % 90),
       timestamp '2025-01-01 10:00:00' + make_interval(mins => g * 37),
       (ARRAY['PAID', 'SHIPPED', 'DONE', 'CANCEL'])[1 + g % 4]
  FROM generate_series(1, 2000) g
ON CONFLICT DO NOTHING;                           -- 2,000건, 그중 DONE 500
