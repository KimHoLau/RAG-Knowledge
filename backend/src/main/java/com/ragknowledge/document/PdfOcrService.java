package com.ragknowledge.document;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * PDF 扫描件 OCR（tess4j 内嵌 Tesseract，纯 Java 依赖、本地 CPU 运行，不依赖外部 tesseract 程序）。
 * 仅当 Tika 提取的文本层不足（扫描件特征）时才触发：整页渲染成图片逐页识别，
 * OCR 文本回填后走既有的切分→向量化→入库管线，切片 metadata、级联删除等行为与普通文档完全一致。
 * 页数超限、语言包缺失、空白扫描件等识别失败统一抛 TextExtractionException（消息直接落库 errorMessage）；
 * PDFBox 装载/渲染与 Tesseract 识别的受检异常保留声明，由提取服务统一收敛。
 */
@Service
public class PdfOcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrService.class);

    private final OcrSettings settings;

    // 类里有两个构造器时 Spring 不会自动选用任一个，而是退回尝试无参构造器（不存在则启动报
    // NoSuchMethodException），必须显式标注装配入口；单构造器的服务（RagService 等）无需此标注
    @Autowired
    public PdfOcrService(@Value("${rag.ocr.enabled}") boolean enabled,
                         @Value("${rag.ocr.tessdata-path}") String tessdataPath,
                         @Value("${rag.ocr.language}") String language,
                         @Value("${rag.ocr.dpi}") int dpi,
                         @Value("${rag.ocr.min-text-length}") int minTextLength,
                         @Value("${rag.ocr.max-pages}") int maxPages) {
        this(new OcrSettings(enabled, tessdataPath, language, dpi, minTextLength, maxPages));
    }

    /** 具名配置直接构造：测试与后续页级路径复用同一入口 */
    PdfOcrService(OcrSettings settings) {
        this.settings = settings;
    }

    /**
     * 是否需要 OCR：仅 PDF 且功能开启，且 Tika 文本少于阈值（正常 PDF 哪怕一页也有上百字符的文本层，
     * 扫描件通常为空或只剩零星水印/页眉字符）。OCR 逐页渲染很慢，绝不能对有文本层的 PDF 白跑一遍。
     */
    public boolean shouldOcr(Path file, String extractedText) {
        if (!settings.enabled() || !isPdf(file)) {
            return false;
        }
        return extractedText == null || extractedText.length() < settings.minTextLength();
    }

    /**
     * 逐页渲染 + 识别，返回整本 PDF 的 OCR 文本（页与页之间以空行分隔）。
     * pageProgress 回调 (已完成页数, 总页数)：入库线程借 chunkCount/chunkTotal 字段上报页级进度，
     * 前端进度条无需改动即可展示 OCR 进度，进入切分阶段后被真实切片数覆盖。
     */
    public String recognizePdf(Path pdf, BiConsumer<Integer, Integer> pageProgress) throws IOException, TesseractException {
        checkTessdata();
        // tess4j 的 Tesseract 实例非线程安全（内部持有指向本地库的状态），入库线程池最多 4 并发，
        // 每次识别新建实例而不是共享，避免并发扫描件互相污染识别结果
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(settings.tessdataPath());
        tesseract.setLanguage(settings.language());
        // doOCR(BufferedImage) 拿不到图片真实 DPI，不声明会按 1 dpi 警告并内部按 70 处理，影响版面分析精度
        tesseract.setVariable("user_defined_dpi", String.valueOf(settings.dpi()));

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            if (settings.maxPages() > 0 && pageCount > settings.maxPages()) {
                throw new TextExtractionException("PDF 共 " + pageCount + " 页，超过 OCR 页数上限 " + settings.maxPages()
                        + "（环境变量 OCR_MAX_PAGES 可调整，设为 0 表示不限制）");
            }
            log.info("开始 OCR 识别：{}，共 {} 页，语言 {}，DPI {}", pdf.getFileName(), pageCount, settings.language(), settings.dpi());
            StringBuilder text = new StringBuilder();
            for (int page = 0; page < pageCount; page++) {
                // DPI 默认 300 是精度与耗时的平衡点（低配机器可降 200）；渲染 RGB 保留原始对比度，二值化交给 Tesseract
                BufferedImage image = renderer.renderImageWithDPI(page, settings.dpi(), ImageType.RGB);
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
                throw new TextExtractionException("OCR 识别完成但未得到任何文本（可能是空白扫描件）");
            }
            log.info("OCR 识别完成：{}，共 {} 页，识别出 {} 字符", pdf.getFileName(), pageCount, result.length());
            return result;
        }
    }

    /** 提前校验语言包就位，缺失时给出可操作的中文指引，而不是等 Tesseract 抛原生英文错误 */
    private void checkTessdata() {
        Path dir = Path.of(settings.tessdataPath());
        if (!Files.isDirectory(dir)) {
            throw new TextExtractionException("OCR 语言包目录不存在：" + dir.toAbsolutePath()
                    + "，请先执行 node scripts/download-tessdata.mjs 下载（或用 OCR_TESSDATA_PATH 指向已有 tessdata 目录）");
        }
        // 语言串形如 chi_sim+eng，逐个检查对应 .traineddata 是否存在
        for (String lang : settings.language().split("\\+")) {
            Path traineddata = dir.resolve(lang.trim() + ".traineddata");
            if (!Files.isRegularFile(traineddata)) {
                throw new TextExtractionException("OCR 语言包缺失：" + traineddata.toAbsolutePath()
                        + "，请执行 node scripts/download-tessdata.mjs 下载");
            }
        }
    }

    private boolean isPdf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf");
    }
}
