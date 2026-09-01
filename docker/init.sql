-- 数据库初始化：启用 pgvector 扩展
-- 说明：Spring AI 的 PgVectorStore 在 initialize-schema=true 时也会尝试自动创建，
-- 这里显式执行一遍以确保扩展就绪。
CREATE EXTENSION IF NOT EXISTS vector;
