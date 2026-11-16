-- 여러 DB 예제 (demo.xdb) MariaDB demo_mart 비우기. demo-reset 이 MariaDB 가 떠 있을 때만 실행한다
USE `demo_mart`;
TRUNCATE TABLE `tb_sequence`;
TRUNCATE TABLE `tb_customer`;
TRUNCATE TABLE `tb_order`;
TRUNCATE TABLE `tb_product`;
TRUNCATE TABLE `tb_stock`;
TRUNCATE TABLE `tb_code`;
