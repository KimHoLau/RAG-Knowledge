<template>
  <div class="page">
    <div class="page-header">
      <h2>知识检索</h2>
      <p>基于 bge-m3 向量相似度，直接检索知识库中的内容切片（不经过大模型）</p>
    </div>

    <div class="search-bar">
      <el-input
        v-model="query"
        placeholder="输入关键词或问题，例如：验收标准是什么"
        size="large"
        clearable
        @keyup.enter="doSearch"
      >
        <template #append>
          <el-button :loading="loading" @click="doSearch">搜索</el-button>
        </template>
      </el-input>
      <el-select v-model="topK" size="large" style="width: 110px">
        <el-option v-for="n in [5, 10, 20]" :key="n" :label="`Top ${n}`" :value="n" />
      </el-select>
    </div>

    <div v-loading="loading" class="results">
      <el-card v-for="r in results" :key="r.index" class="result-card" shadow="never">
        <div class="result-head">
          <el-tag size="small" type="info">#{{ r.index }}</el-tag>
          <span class="doc-name" :title="r.docName">{{ r.docName }}</span>
          <div class="score-wrap">
            <span class="score-text">{{ (r.score * 100).toFixed(1) }}%</span>
            <el-progress
              :percentage="Math.round(r.score * 100)"
              :stroke-width="8"
              :color="scoreColor(r.score)"
              :show-text="false"
              style="width: 120px"
            />
          </div>
        </div>
        <div class="result-content">{{ r.content }}</div>
      </el-card>
      <el-empty v-if="searched && !results.length && !loading" description="未检索到相关内容" />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { searchKnowledge } from '../api'

const query = ref('')
const topK = ref(5)
const results = ref([])
const loading = ref(false)
const searched = ref(false)

async function doSearch() {
  const q = query.value.trim()
  if (!q || loading.value) return
  loading.value = true
  try {
    results.value = await searchKnowledge(q, topK.value)
    searched.value = true
  } finally {
    loading.value = false
  }
}

function scoreColor(score) {
  if (score >= 0.75) return '#67c23a'
  if (score >= 0.5) return '#409eff'
  return '#e6a23c'
}
</script>

<style scoped>
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.result-card {
  margin-bottom: 14px;
}

.result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.doc-name {
  flex: 1;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.score-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.score-text {
  font-size: 13px;
  color: #909399;
  width: 48px;
  text-align: right;
}

.result-content {
  font-size: 14px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow-y: auto;
}
</style>
