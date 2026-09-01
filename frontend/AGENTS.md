# FRONTEND — Vue 3 + Vite

## OVERVIEW
Vue 3.5 + Vite 7 + Element Plus SPA，三视图：知识库管理（/documents）、知识检索（/search）、智能问答（/chat，默认路由）。全部文案与 UI 简体中文。

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| 加 API | `src/api/index.js` | 唯一出口：axios 实例（baseURL `/api`，timeout 120s） |
| SSE 流式 | `src/api/index.js streamChat()` | 手写 fetch+Reader 分帧，**非** EventSource（要 POST body） |
| 路由/标题 | `src/router/index.js` | afterEach 设置 `标题 - RAG 知识库系统` |
| 页面 | `src/views/*.vue` | ChatView 含 sources 引用列表 + markdown 渲染 |

## CONVENTIONS
- API 函数返回 `r.data`（已剥 axios 层）；后端 `code!==0` 由拦截器统一 `ElMessage.error` 并 reject，视图层无需重复判错。
- markdown 渲染用 marked + **DOMPurify 消毒**（ModelResponse 有 XSS 面，勿去掉消毒直接 v-html）。
- 上传进度走 axios `onUploadProgress`；入库状态靠轮询 `/api/documents`（后端异步处理）。

## ANTI-PATTERNS (THIS PROJECT)
- SSE 解析依赖 `\n\n` 分帧 + `data:` 行前缀——后端协议改动（RagService）必须同步 streamChat 的 dispatch。
- 不要绕过 `src/api/index.js` 直接裸 axios/fetch（streamChat 是唯一例外，因需要流式）。
- `frontend/dist/` 为构建产物（已 gitignore 但仓库里存在旧副本），不要手改。

## NOTES
- 开发代理：`/api → http://localhost:8080`（vite.config.js），跨域由代理解决，后端无需 CORS 头。
- 无 lint/test 配置；验证 = `npm run build`。
- Node 要求 ≥20.19。
