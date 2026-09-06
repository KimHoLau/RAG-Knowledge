package com.ragknowledge.document;

import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * PDF 扫描件 OCR（tess4j 内嵌 Tesseract，纯 Java 依赖、本地 CPU 运行，不依赖外部 tesseract 程序）。
 * 仅当 Tika 提取的文本层不足（扫描件特征）时才触发：整页渲染成图片逐页识别，
 * OCR 文本回填后走既有的切分→向量化→入库管线，切片 metadata、级联删除等行为与普通文档完全一致。
 */
@Service
public class PdfOcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrService.class);

    private final boolean enabled;
    private final String tessdataPath;
    private final String language;
    private final int dpi;
    private final int minTextLength;
    private final int maxPages;

    public PdfOcrService(@Value("${rag.ocr.enabled}") boolean enabled,
                         @Value("${rag.ocr.tessdata-path}") String tessdataPath,
                         @Value("${rag.ocr.language}") String language,
                         @Value("${rag.ocr.dpi}") int dpi,
                         @Value("${rag.ocr.min-text-length}") int minTextLength,
                         @Value("${rag.ocr.max-pages}") int maxPages) {
        this.enabled = enabled;
        this.tessdataPath = tessdataPath;
        this.language = language;
        this.dpi = dpi;
        this.minTextLength = minTextLength;
        this.maxPages = maxPages;
    }

    /**
     * 是否需要 OCR：仅 PDF 且功能开启，且 Tika 文本少于阈值（正常 PDF 哪怕一页也有上百字符的文本层，
     * 扫描件通常为空或只剩零星水印/页眉字符）。OCR 逐页渲染很慢，绝不能对有文本层的 PDF 白跑一遍。
     */
    public boolean shouldOcr(Path file, String extractedText) {
        if (!enabled || !isPdf(file)) {
            return false;
        }
        return extractedText == null || extractedText.length() < minTextLength;
    }

    /**
     * 逐页渲染 + 识别，返回整本 PDF 的 OCR 文本（页与页之间以空行分隔）。
     * pageProgress 回调 (已完成页数, 总页数)：入库线程借 chunkCount/chunkTotal 字段上报页级进度，
     * 前端进度条无需改动即可展示 OCR 进度，进入切分阶段后被真实切片数覆盖。
     */
    public String recognizePdf(Path pdf, BiConsumer<Integer, Integer> pageProgress) throws Exception {
        checkTessdata();
        // tess4j 的 Tesseract 实例非线程安全（内部持有指向本地库的状态），入库线程池最多 4 并发，
        // 每次识别新建实例而不是共享，避免并发扫描件互相污染识别结果
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(language);
        // doOCR(BufferedImage) 拿不到图片真实 DPI，不声明会按 1 dpi 警告并内部按 70 处理，影响版面分析精度
        tesseract.setVariable("user_defined_dpi", String.valueOf(dpi));

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            if (maxPages > 0 && pageCount > maxPages) {
                throw new IllegalStateException("PDF 共 " + pageCount + " 页，超过 OCR 页数上限 " + maxPages
                        + "（环境变量 OCR_MAX_PAGES 可调整，设为 0 表示不限制）");
            }
            log.info("开始 OCR 识别：{}，共 {} 页，语言 {}，DPI {}", pdf.getFileName(), pageCount, language, dpi);
            StringBuilder text = new StringBuilder();
            for (int page = 0; page < pageCount; page++) {
                // DPI 默认 300 是精度与耗时的平衡点（低配机器可降 200）；渲染 RGB 保留原始对比度，二值化交给 Tesseract
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank()) {
                    text.append(pageText.strip()).append("\n\n");
                }
                pageProgress.accept(page + 1, pageCount);
                if ((page + 1) % 10 == 0) {
                    log.info("OCR 进度：{}/{} 页（{}）", page + 1, pageCount, pdf.getFileName());
                }
            }
            String result = text.toString().trim();
            if (result.isEmpty()) {
                throw new IllegalStateException("OCR 识别完成但未得到任何文本（可能是空白扫描件）");
            }
            log.info("OCR 识别完成：{}，共 {} 页，识别出 {} 字符", pdf.getFileName(), pageCount, result.length());
            return result;
        }
    }

    /** 提前校验语言包就位，缺失时给出可操作的中文指引，而不是等 Tesseract 抛原生英文错误 */
    private void checkTessdata() {
        Path dir = Path.of(tessdataPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("OCR 语言包目录不存在：" + dir.toAbsolutePath()
                    + "，请先执行 node scripts/download-tessdata.mjs 下载（或用 OCR_TESSDATA_PATH 指向已有 tessdata 目录）");
        }
        // 语言串形如 chi_sim+eng，逐个检查对应 .traineddata 是否存在
        for (String lang : language.split("\\+")) {
            Path traineddata = dir.resolve(lang.trim() + ".traineddata");
            if (!Files.isRegularFile(traineddata)) {
                throw new IllegalStateException("OCR 语言包缺失：" + traineddata.toAbsolutePath()
                        + "，请执行 node scripts/download-tessdata.mjs 下载");
            }
        }
    }

    private boolean isPdf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf");
    }
}
