package com.ragknowledge.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本单元提取服务的边界测试（仓库第一条测试缝隙，拷问定案选 B）：
 * fixture 驱动、不起 Spring 容器、不依赖数据库/向量服务/网络，只断言提取结果的外部行为
 * （数量、顺序、定位信息与关键文本片段），不断言内部实现细节。
 */
class TextExtractionServiceTest {

    /** OCR 关闭：当前缝隙只覆盖 Tika 整篇路径，不依赖 tessdata 与真实扫描件 */
    private final TextExtractionService service =
            new TextExtractionService(new PdfOcrService(OcrSettings.disabled()));

    @Test
    @DisplayName("整篇拍平路径产出单个无定位文本单元")
    void tikaPathProducesSingleUnitWithoutLocation() throws Exception {
        List<TextUnit> units = service.extract(fixture("plain.txt"), (done, total) -> { });

        assertEquals(1, units.size());
        TextUnit unit = units.get(0);
        assertNull(unit.page());
        assertNull(unit.section());
        assertNotNull(unit.text());
        assertTrue(unit.text().contains("企业知识库"), "应包含关键文本");
        assertTrue(unit.text().contains("语义检索"), "应包含关键文本");
    }

    @Test
    @DisplayName("提取不到任何文本时抛出带可操作指引的提取失败异常")
    void blankContentThrowsActionableError() {
        TextExtractionException error = assertThrows(TextExtractionException.class,
                () -> service.extract(fixture("empty.txt"), (done, total) -> { }));

        assertTrue(error.getMessage().contains("无有效文本层"), "错误信息应指向文本层问题而非 OCR 结果");
    }

    /** fixture 从测试 classpath 取真实文件，路径解析交给文件系统（提取服务按需打开文件） */
    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(TextExtractionServiceTest.class.getResource("/fixtures/" + name).toURI());
    }
}
