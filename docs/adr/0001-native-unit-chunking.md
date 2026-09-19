# 按文档原生单元切分（PDF 逐页 / PPT 逐 slide / docx 逐章节）

2026-09-19 拷问定案。入库切分不再一律整篇拍平：PDF 用 PDFBox 逐页提取文本层（扫描页按页触发 OCR 兜底）、PPT 用 POI 按 slide、.docx 用 POI 按 Heading 样式章节，各自以原生单元独立切片，切片 metadata 携带 `page`（PDF/PPT）或 `section`（docx），使引用与检索结果可定位到文件内部。.doc/md/html/txt 维持 Tika 整篇拍平。

## Considered Options

- 全部整篇拍平 + 整篇 OCR 判断（原状）：引用只能到文件级；混合扫描件的扫描页整本丢失。
- Word 伪页码（`lastRenderedPageBreak`/显式分页符）：渲染提示过期、WPS 与程序生成的文档缺失，不可信，故用标题章节替代。
- 自研带 overlap 的切分器：TokenTextSplitter 无 overlap 参数，维护成本高，暂缓。
- Word/PPT 结构化（初版拷问结论）：后经用户决策推翻，Word/PPT 同样纳入原生单元切分。

## Consequences

- 跨页段落会在页边界被切开（换页码精度）；docx 无任何标题时退回整篇切分（section 为空）。
- 切分策略只影响新入库文档，旧文档需重新上传才有定位信息。
- 文本提取两条路径并存：PDF 走 PDFBox，Office 走 POI，其余走 Tika。
- 封面/扉页等短页自然产出小切片，保留（利于按标题检索），由 `minChunkLengthToEmbed=5` 过滤纯空片。
