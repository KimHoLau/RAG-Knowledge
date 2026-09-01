<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon :size="26" color="#409eff"><Collection /></el-icon>
        <span class="logo-text">RAG 知识库</span>
      </div>
      <el-menu router :default-active="activeMenu" class="menu" background-color="#001529" text-color="#a6adb4" active-text-color="#ffffff">
        <el-menu-item index="/documents">
          <el-icon><FolderOpened /></el-icon>
          <span>知识库管理</span>
        </el-menu-item>
        <el-menu-item index="/search">
          <el-icon><Search /></el-icon>
          <span>知识检索</span>
        </el-menu-item>
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能问答</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-footer">Spring AI 2.0.1 · GLM-5.3<br />pgvector · bge-m3</div>
    </el-aside>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  display: flex;
  flex-direction: column;
  background-color: #001529;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px;
}

.logo-text {
  color: #fff;
  font-size: 17px;
  font-weight: 600;
}

.menu {
  flex: 1;
  border-right: none;
}

.menu :deep(.el-menu-item.is-active) {
  background-color: #409eff;
}

.aside-footer {
  padding: 14px 16px;
  color: #5a6a7b;
  font-size: 12px;
  line-height: 1.8;
}

.main {
  padding: 24px;
  overflow-y: auto;
}
</style>
