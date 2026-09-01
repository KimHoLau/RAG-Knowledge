# RAG-Knowledge 知识库系统
![image](https://github.com/KimHoLau/RAG-Knowledge/blob/main/architecture-preview.png)

基于 **Spring AI 2.0.1 + GLM-5.3 + PostgreSQL(pgvector) + bge-m3** 的企业知识库系统，提供资料入库（Word / PPT / PDF）与 RAG 检索问答能力，前端为 Vue 3。

## 功能

- **资料入库**：上传 Word（.doc/.docx）、PowerPoint（.ppt/.pptx）、PDF，自动完成文本提取（Apache Tika）→ 切分（TokenTextSplitter，中文标点友好）→ 向量化（bge-m3）→ 入库（pgvector，HNSW + 余弦距离）。异步处理，前端实时轮询状态；支持删除（级联清理向量切片）。
- **知识检索**：基于 bge-m3 语义相似度直接检索知识库切片，展示来源文档与相似度。
- **智能问答（RAG）**：检索 Top-K 切片作为上下文交给 GLM-5.3 流式生成（SSE），回答标注引用来源，资料不足时如实告知。

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + marked |
| 后端 | Spring Boot 4.1.1 + Spring AI 2.0.1（Java 21） |
| 大模型 | 智谱 GLM-5.3（OpenAI 兼容端点 `https://open.bigmodel.cn/api/paas/v4`） |
| 向量模型 | bge-m3（1024 维，任意 OpenAI 兼容 `/embeddings` 服务，默认本地 Ollama） |
| 数据库 | PostgreSQL 16 + pgvector（HNSW 索引、COSINE 距离） |
| 文档解析 | Spring AI Tika Document Reader（PDF / Word / PPT） |

## 架构

```
┌─────────────┐   /api    ┌───────────────────────────────────────────┐
│  Vue 3 前端  │ ────────▶ │        Spring Boot + Spring AI 2.0.1      │
│ (5173)      │ ◀──────── │                                           │
└─────────────┘  SSE/JSON │  入库：Tika 解析 → Token 切分 → bge-m3 向量化│
                           │        │                │                 │
                           │        ▼                ▼                 │
                           │  ┌───────────┐  ┌──────────────┐          │
                           │  │ PgVector  │  │ bge-m3 服务   │          │
                           │  │ Store     │  │ (OpenAI 兼容) │          │
                           │  └─────┬─────┘  └──────────────┘          │
                           │        ▼                                   │
                           │  问答：向量检索 Top-K → 组装上下文 → GLM-5.3 │
                           └────────┬───────────────────┬──────────────┘
                                    ▼                   ▼
                          PostgreSQL + pgvector   智谱开放平台 (GLM-5.3)
```

## 目录结构

```
RAG-Knowledge/
├── backend/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/ragknowledge/
│       ├── config/AiConfig.java      # GLM-5.3 / bge-m3 客户端与切分器配置
│       ├── document/                 # 资料入库（上传/解析/切分/向量化/删除）
│       ├── rag/                      # RAG 检索、流式问答、知识检索 API
│       └── common/                   # 统一响应与异常处理
├── frontend/                         # Vue 3 前端（知识库管理 / 知识检索 / 智能问答）
├── docker/docker-compose.yml         # PostgreSQL 16 + pgvector
├── scripts/mock-embedding-server.mjs # 离线调试用的 OpenAI 兼容 /embeddings 模拟服务
└── .env.example                      # 环境变量模板
```

## 快速开始

### 0. 前置要求

- JDK 21+、Maven 3.9+（后端）
- Node.js 20.19+（前端）
- Docker & Docker Compose（数据库）
- 智谱开放平台 API Key（https://open.bigmodel.cn）
- 一个 bge-m3 向量服务（见第 2 步，本地 Ollama 最简单）

### 1. 启动数据库（PostgreSQL + pgvector）

```bash
cd docker
docker compose up -d
# 首次启动会自动创建数据库 ragknowledge 并启用 vector 扩展
```

连接信息（与 `docker-compose.yml` 一致，可用环境变量覆盖）：`localhost:5433/ragknowledge`（宿主机端口 5433，避开本机已装 PostgreSQL 服务占用的 5432），用户 `rag`，密码 `rag123456`。向量表 `vector_store` 由 Spring AI 首次启动时自动建表建索引。

### 2. 启动 bge-m3 向量服务（任选其一）

| 方式 | 说明 | EMBEDDING_BASE_URL |
|---|---|---|
| **Ollama（推荐）** | `ollama pull bge-m3` | `http://localhost:11434/v1`（默认） |
| SiliconFlow 云端 | 控制台获取 API Key | `https://api.siliconflow.cn/v1` |
| vLLM / Xinference 自建 | 以 OpenAI 兼容模式启动 bge-m3 | `http://<host>:<port>/v1` |
| 离线调试 | `node scripts/mock-embedding-server.mjs`（伪向量，无语义，仅验证链路） | `http://localhost:9999/v1` |

### 3. 配置并启动后端

```bash
cd backend
export ZHIPUAI_API_KEY=你的智谱APIKey        # Windows: set 或 IDEA 环境变量
mvn spring-boot:run
# 或打包运行：mvn package && java -jar target/rag-knowledge-backend-1.0.0.jar
```

后端默认 `http://localhost:8080`。完整可配置项见 `.env.example`（LLM 端点、向量服务、检索参数 Top-K / 相似度阈值、切分大小等）。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`：先在「知识库管理」上传资料，入库完成后即可使用「知识检索」与「智能问答」。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents/upload` | multipart 上传资料（`file` 字段），返回文档元信息，入库异步进行 |
| GET | `/api/documents` | 文档列表（含入库状态 PROCESSING / COMPLETED / FAILED） |
| DELETE | `/api/documents/{id}` | 删除文档（同时删除其全部向量切片与源文件） |
| POST | `/api/search` | 知识检索，body：`{"query":"...","topK":5}` |
| POST | `/api/chat/stream` | RAG 流式问答（SSE），body：`{"query":"..."}` |

SSE 事件协议（每条 data 为单行 JSON）：

```
data: {"type":"sources","payload":[{"index":1,"docName":"xx.pdf","score":0.83,"content":"..."}]}
data: {"type":"message","payload":"根据"}
data: {"type":"message","payload":"资料[1]……"}
data: {"type":"done","payload":"ok"}
```

## 常见问题

- **扫描件 PDF 入库失败提示“未能提取文本”**：纯图片扫描件无文本层，需先 OCR（Tika 不做 OCR）。
- **检索不到 / 命中过多**：调整 `RAG_SIMILARITY_THRESHOLD`（默认 0.45，调高更严格）与 `RAG_TOP_K`（默认 5）。
- **更换向量服务**：任何 OpenAI 兼容 `/embeddings` 服务均可，通过 `EMBEDDING_BASE_URL / EMBEDDING_API_KEY / EMBEDDING_MODEL` 配置；**向量维度必须保持 1024**，更换维度需清空 `vector_store` 表并重新入库。
- **两个模型为什么不用 starter 自动装配**：对话（智谱）与向量（本地/第三方 bge-m3）使用不同 baseUrl，Spring AI 2.0 的 OpenAI 模块基于官方 OpenAI Java SDK，因此在 `AiConfig` 中手工构建两套 `OpenAIOkHttpClient`。
- **Spring AI 版本要求**：Spring AI 2.0.x 需搭配 Spring Boot 4.x 与 Java 17+（本项目使用 Boot 4.1.1 + Java 21）。

## 已验证

- 后端 `mvn package` 编译打包通过（Spring Boot 4.1.1 + Spring AI 2.0.1，适配 2.0 的官方 OpenAI SDK 客户端 API 与 TokenTextSplitter 新签名）。
- 前端 `npm run build` 构建通过（Vite 7 + Vue 3.5 + Element Plus）。
- 端到端联调（入库 → 检索 → 问答）需要数据库、向量服务与 API Key，请按「快速开始」在本机环境执行。
