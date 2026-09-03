<template>
  <div class="page">
    <div class="page-header">
      <h2>知识库管理</h2>
      <p>支持 Word（.doc / .docx）、PowerPoint（.ppt / .pptx）和 PDF，上传后自动完成文本提取、切分与向量化入库</p>
    </div>

    <div
      class="upload-area"
      :class="{ dragging }"
      @click="fileInput?.click()"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <el-icon :size="42" color="#c0c4cc"><UploadFilled /></el-icon>
      <div class="upload-text">点击或拖拽文件到此处上传</div>
      <div class="upload-tip">支持多文件，单文件最大 100MB</div>
      <input
        ref="fileInput"
        type="file"
        multiple
        accept=".pdf,.doc,.docx,.ppt,.pptx"
        hidden
        @change="onSelect"
      />
    </div>

    <div v-for="item in uploading" :key="item.uid" class="upload-progress">
      <span class="upload-name" :title="item.name">{{ item.name }}</span>
      <el-progress
        :percentage="item.progress"
        :status="item.status === 'error' ? 'exception' : item.status === 'done' ? 'success' : undefined"
        style="flex: 1; margin: 0 12px"
      />
      <span class="upload-status">
        <template v-if="item.status === 'error'">{{ item.error }}</template>
        <template v-else-if="item.status === 'done'">上传完成，入库中…</template>
        <template v-else>上传中…</template>
      </span>
    </div>

    <el-table v-loading="loading" :data="documents" stripe>
      <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small">{{ row.fileType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="切片数" width="90" align="center">
        <template #default="{ row }">
          <span v-if="isProcessing(row) && row.chunkTotal > 0">{{ row.chunkCount }}/{{ row.chunkTotal }}</span>
          <span v-else>{{ row.chunkCount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="入库进度" min-width="200" align="center">
        <template #default="{ row }">
          <!-- chunkTotal 为 0 表示还在解析切分阶段、总数未知，用不确定态进度条 -->
          <el-progress
            v-if="isProcessing(row)"
            :percentage="progressPercent(row)"
            :indeterminate="row.chunkTotal === 0"
            :format="() => progressText(row)"
            :stroke-width="14"
          />
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.status === 'FAILED' && row.errorMessage"
            :content="row.errorMessage"
            placement="top"
          >
            <el-tag type="danger">入库失败</el-tag>
          </el-tooltip>
          <el-tag v-else-if="row.status === 'COMPLETED'" type="success">已完成</el-tag>
          <el-tag v-else type="warning">入库中…</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="180">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <el-button type="primary" link :loading="downloadingId === row.id" @click="onDownload(row)">
            下载
          </el-button>
          <el-popconfirm
            title="确认删除该资料？"
            confirm-button-text="删除"
            cancel-button-text="取消"
            @confirm="onDelete(row)"
          >
            <template #reference>
              <el-button type="danger" link>删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无资料，请先上传" />
      </template>
    </el-table>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deleteDocument, downloadDocument, listDocuments, uploadDocument } from '../api'

const ALLOWED = /\.(pdf|docx?|pptx?)$/i

const fileInput = ref(null)
const dragging = ref(false)
const loading = ref(false)
const documents = ref([])
const uploading = ref([])
const downloadingId = ref('')
let pollTimer = null

onMounted(async () => {
  await load()
  ensurePolling()
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})

async function load() {
  loading.value = true
  try {
    documents.value = await listDocuments()
  } finally {
    loading.value = false
  }
}

function ensurePolling() {
  if (pollTimer) return
  if (!documents.value.some(isProcessing)) return
  pollTimer = setInterval(async () => {
    try {
      documents.value = await listDocuments()
    } catch {
      // 单次轮询失败保持定时器，下一轮继续尝试
      return
    }
    if (!documents.value.some(isProcessing)) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }, 1500)
}

function isProcessing(row) {
  return row.status === 'PROCESSING'
}

function progressPercent(row) {
  if (!row.chunkTotal) return 0
  return Math.min(100, Math.floor(((row.chunkCount ?? 0) / row.chunkTotal) * 100))
}

function progressText(row) {
  return row.chunkTotal ? `${row.chunkCount ?? 0}/${row.chunkTotal}` : '解析中…'
}

function onDrop(e) {
  dragging.value = false
  handleFiles(e.dataTransfer?.files || [])
}

function onSelect(e) {
  handleFiles(e.target.files || [])
  e.target.value = ''
}

async function handleFiles(fileList) {
  const files = Array.from(fileList)
  const valid = files.filter((f) => ALLOWED.test(f.name))
  const skipped = files.length - valid.length
  if (skipped > 0) ElMessage.warning(`已跳过 ${skipped} 个不支持的文件（仅支持 pdf/doc/docx/ppt/pptx）`)
  for (const file of valid) {
    const item = reactive({ uid: Date.now() + Math.random(), name: file.name, progress: 0, status: 'uploading', error: '' })
    uploading.value.push(item)
    try {
      await uploadDocument(file, (p) => (item.progress = Math.max(item.progress, p)))
      item.progress = 100
      item.status = 'done'
    } catch (e) {
      item.status = 'error'
      item.error = e?.message || '上传失败'
    }
    await load()
    ensurePolling()
  }
  setTimeout(() => {
    uploading.value = uploading.value.filter((i) => i.status === 'error')
  }, 5000)
}

async function onDownload(row) {
  downloadingId.value = row.id
  try {
    await downloadDocument(row.id, row.fileName)
  } catch {
    // 失败提示已在 api 层统一弹出，这里只负责复位按钮状态
  } finally {
    downloadingId.value = ''
  }
}

async function onDelete(row) {
  await deleteDocument(row.id)
  ElMessage.success('已删除')
  await load()
}

function fmtSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function fmtTime(t) {
  return t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '-'
}
</script>

<style scoped>
.upload-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 28px;
  margin-bottom: 16px;
  border: 1px dashed #c0c4cc;
  border-radius: 8px;
  background: #fafbfc;
  cursor: pointer;
  transition: border-color 0.2s;
}

.upload-area:hover,
.upload-area.dragging {
  border-color: #409eff;
  background: #ecf5ff;
}

.upload-text {
  color: #606266;
  font-size: 15px;
}

.upload-tip {
  color: #909399;
  font-size: 12px;
}

.upload-progress {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.upload-name {
  width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.upload-status {
  width: 180px;
  font-size: 12px;
  color: #909399;
}

.muted {
  color: #c0c4cc;
}
</style>
