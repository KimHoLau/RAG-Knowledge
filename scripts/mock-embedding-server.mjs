// 最小化 OpenAI 兼容 /embeddings 模拟服务（仅用于离线联调入库/检索链路，向量无真实语义！）
// 用法：node scripts/mock-embedding-server.mjs [port]
// 后端配套配置：EMBEDDING_BASE_URL=http://localhost:9999/v1  EMBEDDING_MODEL=qwen3-embedding:4b
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

// 基于 token 哈希的确定性伪向量：相同文本得到相同向量，字面重叠的文本有一定相似度
function embed(text) {
  const v = new Array(DIM).fill(0)
  for (const token of String(text).split(/[\s,.;:!?，。；：！？、]+/)) {
    if (!token) continue
    let h = fnv1a(token)
    for (let k = 0; k < 4; k++) {
      h = fnv1a(`${h}:${k}`)
      v[h % DIM] += 1
    }
  }
  const norm = Math.sqrt(v.reduce((a, b) => a + b * b, 0)) || 1
  return v.map((x) => x / norm)
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url.includes('/embeddings')) {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: { message: 'not found' } }))
    return
  }
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    let input = []
    try {
      input = JSON.parse(body).input
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'invalid json' } }))
      return
    }
    if (typeof input === 'string') input = [input]
    const data = input.map((text, i) => ({
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
  })
})

server.listen(port, () => {
  console.log(`[mock-embedding-server] listening on http://localhost:${port}/v1/embeddings (dim=${DIM})`)
})
