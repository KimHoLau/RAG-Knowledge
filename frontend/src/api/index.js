import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 120000 })

// 统一处理后端返回的 {code, message, data}
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (error) => {
    const msg = error.response?.data?.message || error.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(new Error(msg))
  },
)

export function listDocuments() {
  return http.get('/documents').then((r) => r.data)
}

export function uploadDocument(file, onProgress) {
  const form = new FormData()
  form.append('file', file)
  return http
    .post('/documents/upload', form, {
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
    .then((r) => r.data)
}

export function deleteDocument(id) {
  return http.delete(`/documents/${id}`)
}

export function searchKnowledge(query, topK) {
  return http.post('/search', { query, topK }).then((r) => r.data)
}

/**
 * RAG 流式问答。SSE 事件格式见后端 RagService：
 * {"type":"sources"|"message"|"done"|"error", payload}
 */
export async function streamChat({ query, onSources, onDelta, onDone, onError }) {
  let resp
  try {
    resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query }),
    })
  } catch (e) {
    onError?.('无法连接服务端，请确认后端已启动')
    return
  }
  if (!resp.ok || !resp.body) {
    let msg = `请求失败（HTTP ${resp.status}）`
    try {
      const body = await resp.json()
      if (body?.message) msg = body.message
    } catch {
      /* 忽略非 JSON 响应 */
    }
    onError?.(msg)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatch = (evt) => {
    if (!evt || !evt.type) return
    if (evt.type === 'sources') onSources?.(evt.payload || [])
    else if (evt.type === 'message') onDelta?.(String(evt.payload ?? ''))
    else if (evt.type === 'done') onDone?.()
    else if (evt.type === 'error') onError?.(evt.payload || '生成失败')
  }

  const handleChunkText = (chunk) => {
    buffer += chunk
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const dataLine = raw.split('\n').find((l) => l.startsWith('data:'))
      if (!dataLine) continue
      try {
        dispatch(JSON.parse(dataLine.slice(5).trim()))
      } catch {
        /* 跳过无法解析的事件 */
      }
    }
  }

  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    handleChunkText(decoder.decode(value, { stream: true }))
  }
  onDone?.()
}
