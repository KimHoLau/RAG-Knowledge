package com.ragknowledge.document;

/**
 * OCR 配置（rag.ocr.* 六项）：捆绑成一个具名对象，构造点不再出现读不出含义的裸参数串。
 * enabled=false 时 shouldOcr 直接短路，其余字段不参与任何行为。
 */
public record OcrSettings(boolean enabled, String tessdataPath, String language, int dpi, int minTextLength, int maxPages) {

    /** 功能关闭的最小配置：供不依赖 tessdata 与真实扫描件的测试使用 */
    public static OcrSettings disabled() {
        return new OcrSettings(false, "./data/tessdata", "chi_sim+eng", 300, 100, 0);
    }
}
