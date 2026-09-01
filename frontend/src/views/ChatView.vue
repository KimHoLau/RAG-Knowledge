<template>
  <div class="chat-page">
    <div class="chat-header">
      <div class="page-header">
        <h2>智能问答</h2>
        <p>基于知识库内容回答（GLM-5.3 + RAG），回答附带引用来源</p>
      </div>
      <el-button size="small" @click="clearChat">清空对话</el-button>
    </div>

    <div ref="listRef" class="chat-list">
      <el-empty v-if="!messages.length" description="向知识库提问吧，例如：这份资料的核心结论是什么" />
      <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role">
        <div class="avatar" :class="m.role">
          <el-icon v-if="m.role === 'user'" :size="18"><User /></el-icon>
          <el-icon v-else :size="18"><ChatDotRound /></el-icon>
        </div>
        <div class="bubble" :class="m.role">
          <div v-if="m.role === 'assistant'" class="md" v-html="renderMd(m.content)"></div>
          <template v-else>{{ m.content }}</template>

          <div v-if="m.role === 'assistant' && m.streaming && !m.content" class="typing">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在检索知识库…
          </div>

          <div v-if="m.role === 'assistant' && m.sources?.length && !m.streaming" class="sources">
            <el-collapse>
              <el-collapse-item :title="`引用来源（${m.sources.length}）`">
                <div v-for="s in m.sources" :key="s.index" class="source-item">
                  <div class="source-head">
                    <el-tag size="small" type="info">[{{ s.index }}]</el-tag>
                    <span class="source-name" :title="s.docName">{{ s.docName }}</span>
                    <span class="source-score">{{ (s.score * 100).toFixed(1) }}%</span>
                  </div>
                  <div class="source-content">{{ short(s.content, 220) }}</div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入问题，Enter 发送，Shift + Enter 换行"
        :disabled="sending"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="sending" :disabled="!input.trim()" @click="send">
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUpdated, reactive, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { streamChat } from '../api'

marked.setOptions({ breaks: true, gfm: true })

const input = ref('')
const sending = ref(false)
const messages = ref([])
const listRef = ref(null)

onMounted(scrollToBottom)
onUpdated(scrollToBottom)

async function send() {
  const query = input.value.trim()
  if (!query || sending.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: query })
  const assistant = reactive({ role: 'assistant', content: '', sources: [], streaming: true })
  messages.value.push(assistant)
  sending.value = true
  let errored = false
  try {
    await streamChat({
      query,
      onSources: (sources) => {
        assistant.sources = sources
      },
      onDelta: (delta) => {
        assistant.content += delta
      },
      onError: (msg) => {
        errored = true
        assistant.content += assistant.content
          ? `\n\n---\n**生成中断：**${msg}`
          : `**生成失败：**${msg}`
      },
    })
  } finally {
    assistant.streaming = false
    sending.value = false
    if (errored && !assistant.sources.length && !assistant.content.startsWith('**')) {
      assistant.content = `**生成失败：**请检查后端与大模型配置`
    }
    await nextTick()
    scrollToBottom()
  }
}

function clearChat() {
  if (sending.value) return
  messages.value = []
}

function renderMd(text) {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text))
}

function short(text, n) {
  return text.length > n ? `${text.slice(0, n)}…` : text
}

function scrollToBottom() {
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}
</script>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: 960px;
  margin: 0 auto;
}

.chat-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.chat-header .page-header {
  margin-bottom: 12px;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 4px;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
}

.avatar.user {
  background: #409eff;
}

.avatar.assistant {
  background: #67c23a;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble.user {
  background: #ecf5ff;
  color: #303133;
}

.bubble.assistant {
  background: #fff;
  border: 1px solid #ebeef5;
  white-space: normal;
}

.typing {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #909399;
  font-size: 13px;
}

.sources {
  margin-top: 10px;
  border-top: 1px dashed #ebeef5;
  padding-top: 6px;
}

.sources :deep(.el-collapse) {
  border: none;
}

.sources :deep(.el-collapse-item__header) {
  font-size: 13px;
  color: #909399;
  height: 32px;
  line-height: 32px;
  background: transparent;
  border: none;
}

.sources :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}

.source-item {
  margin-bottom: 10px;
}

.source-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.source-name {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-score {
  font-size: 12px;
  color: #909399;
}

.source-content {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.chat-input {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}

.chat-input .el-button {
  height: 54px;
}
</style>
