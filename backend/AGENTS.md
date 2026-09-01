# BACKEND — Spring Boot + Spring AI 2.0

## OVERVIEW
Spring Boot 4.1.1 + Spring AI 2.0.1（Java 21, Maven），四包分层：`config`（模型客户端）、`common`（响应/异常）、`document`（入库）、`rag`（检索问答）。

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 检索/提示词/SSE | `rag/RagService.java` | /api/search 不过阈值；/api/chat 检索过阈值（0.45），刻意差异 |
| SSE 端点 | `rag/ChatController.java` | `POST /api/chat/stream`，`Flux<ServerSentEvent<String>>` |
| 入库管线 | `document/DocumentIngestService.java` | `@Async`，异常吞掉并回写 FAILED 状态，不向上抛 |
| 上传/删除 | `document/DocumentService.java` | 删除走原生 SQL 删 `vector_store`（metadata->>'doc_id'） |
| 模型客户端 | `config/AiConfig.java` | 全部 Bean 在此，改 baseUrl/model 从 `rag.*` 配置注入 |

## FLOWS
- 入库：upload 落盘（`{uuid}_{原名}`）→ 实体 PROCESSING → @Async ingest：Tika 提取（全量读内存）→ TokenTextSplitter（800/300，中文标点分隔符）→ 按批 8 条 `vectorStore.add` → COMPLETED/FAILED。
- 问答：retrieve（topK+阈值）→ sources 事件 → system 提示词（含 [n] 编号资料）→ GLM 流式 → message 事件 → done；任何异常 → error 事件，连接不断。

## CONVENTIONS
- `rag.*` 配置项经 `@Value` 注入，yml 里全部带 `${ENV:default}`；新增配置必须同步 `.env.example`。
- 业务错误抛 `BizException`（中文消息），由 GlobalExceptionHandler 统一包成 `ApiResult`；错误消息会直接透出到前端 UI。
- 流式路径必须同时给 ChatModel 配同步+异步客户端（AiConfig 已处理），缺异步则 `.stream()` 失败。

## ANTI-PATTERNS (THIS PROJECT)
- `@Async` 依赖主类 `@EnableAsync`（RagKnowledgeApplication），移除会导致入库阻塞请求线程。
- 错误消息落库截断 1900 字符（DB 列 2000 留余量），勿加长。
- （模型客户端与向量维度约束见根 AGENTS.md，改 AiConfig 前必读。）

## NOTES
- 原始文件存 `rag.storage-dir`（默认 `./data/uploads`，相对后端工作目录），gitignore 已排除。
- 无测试目录；验证 = `mvn package`。
- 日志 `com.ragknowledge` 为 DEBUG，排障直接看控制台。
