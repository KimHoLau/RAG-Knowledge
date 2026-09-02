package com.ragknowledge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后补齐按文档清理向量所需的索引。
 *
 * <p>删除文档、入库失败回滚都要按 {@code metadata->>'doc_id'} 过滤，而 metadata 是 JSON 列，
 * 没有索引时每次都是全表扫描。索引建在 {@code (metadata::jsonb ->> 'doc_id')} 表达式上：
 * 该表达式是 immutable 的，可以被索引使用。
 *
 * <p>放在 ApplicationRunner 里而不是 init.sql：vector_store 表由 Spring AI 在 Bean 初始化阶段创建，
 * 数据库容器首次执行 init.sql 时它还不存在。这里用 DO 块做存在性判断，表缺失时直接跳过；
 * 任何失败只记日志，不阻断启动。
 */
@Component
public class VectorStoreIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreIndexInitializer.class);

    private static final String CREATE_INDEX_SQL = """
            DO $$
            BEGIN
              IF to_regclass('%s') IS NOT NULL THEN
                CREATE INDEX IF NOT EXISTS %s ON %s (((metadata::jsonb) ->> 'doc_id'));
              END IF;
            END $$;
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String sql;

    public VectorStoreIndexInitializer(JdbcTemplate jdbcTemplate,
                                       @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
                                       @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        String table = schemaName + "." + tableName;
        this.sql = CREATE_INDEX_SQL.formatted(table, "idx_" + tableName + "_doc_id", table);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute(sql);
            log.info("向量库按文档清理索引已就绪");
        } catch (Exception e) {
            log.warn("创建向量库索引失败，按文档删除将退化为全表扫描：{}", e.getMessage());
        }
    }
}
