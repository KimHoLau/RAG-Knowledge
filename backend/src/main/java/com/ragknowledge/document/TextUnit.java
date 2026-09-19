package com.ragknowledge.document;

/**
 * 文本单元：各格式提取路径产出的最小切分输入，携带文档内定位信息。
 * page 为 PDF 页序 / PPT slide 序，section 为 docx 就近标题；整篇拍平路径（Tika）两者为空。
 * 定位随切片 metadata 入库，供引用与检索结果展示"第 N 页 / § 标题"（ADR-0001）。
 */
public record TextUnit(String text, Integer page, String section) {
}
