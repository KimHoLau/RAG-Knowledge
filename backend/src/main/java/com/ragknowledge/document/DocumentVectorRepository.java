package com.ragknowledge.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 向量库（vector_store 表）按文档的维护操作。
 *
 * <p>表名由 Spring AI 自动创建并维护，这里只做业务侧的按文档清理，集中一处便于同步修改。
 *
 * <p><b>为什么要 ::jsonb</b>：Spring AI 2.0.1 建表时 metadata 列是 {@code json} 而非 {@code jsonb}，
 * 而 PostgreSQL 的 {@code ->>} 取文本操作符只对 jsonb 定义，直接写 {@code metadata->>'doc_id'} 会报
 * “operator does not exist: json ->> unknown”。先转成 jsonb 后，列本来就是 jsonb 时也无副作用，
 * 并且该表达式是 immutable 的，可以建表达式索引（见 VectorStoreIndexInitializer）。
 */
@Repository
public class DocumentVectorRepository {

    private static final String DELETE_BY_DOC_ID_SQL =
            "DELETE FROM public.vector_store WHERE metadata::jsonb ->> 'doc_id' = ?";

    private final JdbcTemplate jdbcTemplate;

    public DocumentVectorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 删除某文档写入向量库的全部切片，返回被删除的行数 */
    public int deleteByDocId(String docId) {
        return jdbcTemplate.update(DELETE_BY_DOC_ID_SQL, docId);
    }
}
