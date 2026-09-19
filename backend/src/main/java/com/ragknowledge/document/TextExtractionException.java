package com.ragknowledge.document;

/**
 * 文本提取阶段的统一失败出口（ADR-0001）：各格式提取路径（Tika 解析、PDF 渲染、OCR 识别）的失败
 * 都收敛为这一个运行时异常，调用方无需感知 tess4j / PDFBox 等实现类型，签名也就不会随新增格式路径膨胀。
 * 消息为可直接落库 errorMessage 的中文描述——入库线程捕获后走 FAILED + errorMessage 链路，
 * 不经全局异常处理器，因此消息必须是面向用户的可操作指引而非堆栈摘要。
 */
public class TextExtractionException extends RuntimeException {

    public TextExtractionException(String message) {
        super(message);
    }

    public TextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
