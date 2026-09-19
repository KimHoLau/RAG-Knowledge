package com.ragknowledge.document;

import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 文本单元提取服务：入库管线的文本来源出口，各格式的提取路径在此汇成统一的"文本单元"列表
 * （文本 + 可空定位），之后共用切分 → 向量化 → pgvector 管线（ADR-0001）。
 * 当前仅有 Tika 整篇拍平路径（.doc/.txt/.md/html 及未改造格式均走此路）；
 * PDF 逐页 / PPT 逐 slide / docx 逐章节路径由后续票接入。
 * 扫描件 PDF 的整本 OCR 兜底暂挂在提取服务内（Tika 文本过少时触发），
 * 页级兜底改造后整本循环移入 PDF 页提取路径、本服务只保留单页识别职责的调用。
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    private final PdfOcrService ocrService;

    public TextExtractionService(PdfOcrService ocrService) {
        this.ocrService = ocrService;
    }

    /**
     * 提取文本单元列表。
     * pageProgress 回调 (已完成页数, 总页数) 仅在 OCR 兜底阶段有回调值：
     * OCR 是分钟级耗时操作，页级进度借 chunkCount/chunkTotal 字段上报，前端同一进度条展示。
     */
    public List<TextUnit> extract(Path file, BiConsumer<Integer, Integer> pageProgress)
            throws IOException, TesseractException {
        String text = tikaText(file);
        boolean ocrAttempted = false;
        if (ocrService.shouldOcr(file, text)) {
            log.info("检测到扫描件 PDF（Tika 文本 {} 字符），转 OCR 识别：{}", text.length(), file.getFileName());
            text = ocrService.recognizePdf(file, pageProgress);
            ocrAttempted = true;
        }
        if (text.isBlank()) {
            // 未走 OCR 时（功能关闭或非 PDF）如实说"无文本层"，不要误报成 OCR 的识别结果
            throw new IllegalStateException(ocrAttempted
                    ? "未能从文件中提取到文本内容（OCR 识别后仍无有效文本）"
                    : "未能从文件中提取到文本内容（文档无有效文本层；若为扫描件 PDF 请确认 OCR 已开启并已下载语言包）");
        }
        return List.of(new TextUnit(text, null, null));
    }

    private String tikaText(Path file) {
        // 交给 Resource 按需打开文件，避免把整个文件（上限 100MB）一次性读进堆
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
        return reader.read().stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }
}
