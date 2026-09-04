# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-01
**Commit:** f44dd72
**Branch:** main

## OVERVIEW
企业知识库系统：上传 Word/PPT/PDF → Tika 解析 → 切分 → qwen3-embedding:4b 向量化 → pgvector 入库，支持语义检索与 GLM-5.3 流式 RAG 问答（SSE）。后端 Spring Boot 4.1.1 + Spring AI 2.0.1（Java 21），前端 Vue 3 + Vite 7 + Element Plus。

## STRUCTURE
```
RAG-Knowledge/
├── backend/     # Spring Boot 后端（详见 backend/AGENTS.md）
│   └── src/main/java/com/ragknowledge/{config,common,document,rag}
├── frontend/    # Vue 3 SPA（详见 frontend/AGENTS.md）
├── docker/      # PostgreSQL 16 + pgvector（compose + init.sql，仅建 vector 扩展）
├── scripts/mock-embedding-server.mjs  # 离线联调用的伪 /embeddings 服务（无真实语义）
└── .env.example # 全部环境变量模板（唯一配置真源，application.yml 只做默认值）
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 改检索/问答行为 | `backend/.../rag/RagService.java` | Top-K、相似度阈值、系统提示词、SSE 协议都在这一个类 |
| 改入库流程 | `backend/.../document/DocumentIngestService.java` | Tika→切分→批量向量化→状态回写 |
| 改模型接入 | `backend/.../config/AiConfig.java` | 手工构建两套 OpenAI 客户端，勿用 starter 自动装配 |
| 改 API | 各 Controller | 统一返回 `ApiResult{code,message,data}`，code!==0 即业务错误 |
| 改环境变量/端口 | `.env.example` + `application.yml` | yml 中 `${ENV:default}` 形式 |
| 本地起库 | `docker/docker-compose.yml` | 宿主机端口 **5433**（非 5432） |

## CODE MAP
| Symbol | Type | Location | Role |
|--------|------|----------|------|
| RagService | Service | rag/RagService.java | RAG 核心：检索→组装上下文→GLM 流式；定义 SSE 事件协议（sources/message/done/error） |
| AiConfig.tokenTextSplitter | Bean | config/AiConfig.java:71 | 中文标点切分边界；被 DocumentIngestService 调用 |
| AiConfig.chatModel/embeddingModel | Bean | config/AiConfig.java | GLM-5.3（/api/paas/v4）与 qwen3-embedding:4b（Ollama /v1）两套 baseUrl，需同步+异步双客户端 |
| DocumentService | Service | document/ | 上传落盘、状态机、删除级联（向量切片+源文件） |
| DocumentIngestService | Service(@Async) | document/ | 异步入库管线；metadata 写 doc_id/doc_name 供溯源与级联删除 |
| DocumentStatus | Enum | document/ | PROCESSING→COMPLETED/FAILED，7 处引用，前端轮询依赖 |
| ApiResult / GlobalExceptionHandler | common/ | 统一响应与异常出口，前端拦截器按 code!==0 报错 |

## CONVENTIONS
- 注释、日志、提示词、UI 文案全部简体中文。
- 代码注释解释"为什么"（端口为何 5433、为何不用 starter 等），改行为时同步更新注释。
- 新配置一律 `application.yml` 中 `${ENV_VAR:default}`，并在 `.env.example` 补一行说明。
- 后端 DTO 用 record；Controller 薄、Service 承载逻辑；构造器注入。
- 前端 API 调用全部走 `frontend/src/api/index.js` 的 axios 实例，不得散落裸 axios。

## ANTI-PATTERNS (THIS PROJECT)
- **向量输出维度必须保持 1024**：后端固定请求 `dimensions=1024`，qwen3-embedding:4b 原生 2560 维由该参数截断。换不识别 `dimensions` 参数的模型或改维度，必须清空 `vector_store` 表并全部重新入库。
- **不要**给对话/向量模型用 Spring AI openai starter 自动装配——两者 baseUrl 不同，必须在 `AiConfig` 手工构建 `OpenAIOkHttpClient`（Spring AI 2.0 包装官方 OpenAI Java SDK）。
- **不要**手工建 `vector_store` 表（`initialize-schema: true` 自动建）；init.sql 只负责 `CREATE EXTENSION vector`。
- Tika 不做 OCR：扫描件 PDF 无文本层，入库会 FAILED，属预期行为。
- SSE data 必须是**单行 JSON**（前端按 `\n\n` 分帧再解析 `data:` 行）。
- 删文档必须级联：`vector_store` 按 `metadata->>'doc_id'` 删 + 删源文件 + 删实体，三处缺一不可。
- 不要提交 `.env`、`backend/data/`（已 gitignore，含 API Key 与用户上传件）。

## UNIQUE STYLES
- SSE 事件协议（前后端契约，改动需两侧同步）：`{"type":"sources"|"message"|"done"|"error","payload":...}`，sources 先于 message 下发。
- 系统提示词强制中文回答 + `[1][2]` 引用标注 + 资料不足时如实拒答（RagService.SYSTEM_PROMPT_TEMPLATE）。
- 无数据库迁移工具：JPA `ddl-auto: update` + Spring AI 自动建表（小项目约定，改表结构需评估存量数据）。

## COMMANDS
```bash
# 数据库（PostgreSQL 16 + pgvector，端口 5433）
cd docker && docker compose up -d

# 后端（需先 export ZHIPUAI_API_KEY=...）
cd backend && mvn spring-boot:run          # http://localhost:8080
cd backend && mvn package                   # 打包验证

# 向量服务（三选一）
ollama pull qwen3-embedding:4b-q4_K_M    # 默认 http://localhost:11434/v1
node scripts/mock-embedding-server.mjs      # 离线伪向量 http://localhost:9999/v1

# 前端
cd frontend && npm install && npm run dev   # http://localhost:5173，/api 代理到 8080
cd frontend && npm run build                # 构建验证
```

## NOTES
- 无任何测试（无 src/test、无前端测试），无 CI——验证靠 `mvn package` + `npm run build` + 手动端到端。
- 端到端联调需三件套齐备：数据库、向量服务、智谱 API Key；缺 Key 时后端能起但问答必失败。
- 检索调参：`RAG_TOP_K`（默认 5）、`RAG_SIMILARITY_THRESHOLD`（默认 0.45）；/api/search 不过阈值，/api/chat 检索过阈值，两者是故意的差异。
- Spring AI 2.0.x 必须搭配 Spring Boot 4.x；TokenTextSplitter 构造器签名与 1.x 不同。
