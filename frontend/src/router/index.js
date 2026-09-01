import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/chat' },
  {
    path: '/documents',
    name: 'documents',
    component: () => import('../views/KnowledgeBaseView.vue'),
    meta: { title: '知识库管理' },
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('../views/KnowledgeSearchView.vue'),
    meta: { title: '知识检索' },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '智能问答' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - RAG 知识库系统` : 'RAG 知识库系统'
})

export default router
