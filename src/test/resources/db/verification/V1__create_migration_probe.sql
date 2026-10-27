-- 仅供自动化与隔离数据库验收，不属于生产数据库版本历史。
CREATE TABLE migration_probe (
    id BIGINT PRIMARY KEY,
    marker VARCHAR(64) NOT NULL
);
INSERT INTO migration_probe (id, marker) VALUES (1, 'flyway-verification');
