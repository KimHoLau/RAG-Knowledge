package com.ragknowledge.document;

import java.util.HashMap;
import java.util.Map;

/**
 * 文本单元：各格式提取路径产出的最小切分输入，携带文档内定位信息。
 * page 为 PDF 页序 / PPT slide 序，section 为 docx 就近标题；整篇拍平路径（Tika）两者为空。
 * 定位随切片 metadata 入库，供引用与检索结果展示"第 N 页 / § 标题"（ADR-0001）。
 */
public record TextUnit(String text, Integer page, String section) {

    /**
     * 切片 metadata：doc_id/doc_name 供溯源展示与按文档级联删除；
     * page/section 有值才写入，整篇拍平路径的切片不带定位键。
     */
    public Map<String, Object> toMetadata(String docId, String docName) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", docId);
        metadata.put("doc_name", docName);
        if (page != null) {
            metadata.put("page", page);
        }
        if (section != null) {
            metadata.put("section", section);
        }
        return metadata;
    }
}
