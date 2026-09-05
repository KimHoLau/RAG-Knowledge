// 最小化 OpenAI 兼容 /embeddings + Jina/Cohere 风格 /rerank 模拟服务（仅用于离线联调入库/检索链路，无真实语义！）
// 用法：node scripts/mock-embedding-server.mjs [port]
// 后端配套配置：EMBEDDING_BASE_URL=http://localhost:9999/v1  EMBEDDING_MODEL=qwen3-embedding:4b
//               RERANK_BASE_URL=http://localhost:9999/v1     RERANK_MODEL=qwen3-reranker:mock
import http from 'node:http'

const DIM = 1024
const port = Number(process.argv[2] || process.env.PORT || 9999)

function fnv1a(s) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

function tokenize(text) {
  return String(text).split(/[\s,.;:!?，。；：！？、]+/).filter(Boolean)
}

// 基于 token 哈希的确定性伪向量：相同文本得到相同向量，字面重叠的文本有一定相似度
function embed(text) {
  const v = new Array(DIM).fill(0)
  for (const token of tokenize(text)) {
    let h = fnv1a(token)
    for (let k = 0; k < 4; k++) {
      h = fnv1a(`${h}:${k}`)
      v[h % DIM] += 1
    }
  }
  const norm = Math.sqrt(v.reduce((a, b) => a + b * b, 0)) || 1
  return v.map((x) => x / norm)
}

// 伪相关度：query 的二元字符组（bigram）在文档中的覆盖率（0~1）。
// 中文无分词，按词项重叠会整句 0 分，改用字符 bigram 保证离线排序大体合理
function relevanceScore(query, doc) {
  const grams = (s) => {
    const t = String(s).replace(/\s+/g, '')
    const out = new Set()
    if (t.length < 2) {
      if (t) out.add(t)
      return out
    }
    for (let i = 0; i < t.length - 1; i++) out.add(t.slice(i, i + 2))
    return out
  }
  const q = grams(query)
  if (q.size === 0) return 0
  const d = grams(doc)
  let hit = 0
  for (const g of q) if (d.has(g)) hit++
  return hit / q.size
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !(req.url.includes('/embeddings') || req.url.includes('/rerank'))) {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: { message: 'not found' } }))
    return
  }
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    let payload
    try {
      payload = JSON.parse(body)
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'invalid json' } }))
      return
    }
    if (req.url.includes('/embeddings')) {
      let input = payload.input
      if (typeof input === 'string') input = [input]
      const data = (input || []).map((text, i) => ({
        object: 'embedding',
        index: i,
        embedding: embed(text),
      }))
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({
        object: 'list',
        data,
        model: 'mock-qwen3-embedding',
        usage: { prompt_tokens: 0, total_tokens: 0 },
      }))
      return
    }
    // /rerank：按伪相关度降序返回，只保留 top_n 条（协议同 llama.cpp /v1/rerank）
    let documents = payload.documents || []
    if (typeof documents === 'string') documents = [documents]
    const topN = Number.isInteger(payload.top_n) && payload.top_n > 0 ? payload.top_n : documents.length
    const results = documents
      .map((doc, index) => ({ index, relevance_score: relevanceScore(payload.query, doc) }))
      .sort((a, b) => b.relevance_score - a.relevance_score)
      .slice(0, topN)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ model: 'mock-qwen3-reranker', object: 'list', results }))
  })
})

server.listen(port, () => {
  console.log(`[mock-embedding-server] listening on http://localhost:${port}/v1/embeddings (dim=${DIM})`)
  console.log(`[mock-embedding-server] listening on http://localhost:${port}/v1/rerank`)
})
