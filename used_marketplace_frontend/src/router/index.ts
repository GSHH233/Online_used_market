import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const USER_HOME = '/market'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/auth/LoginView.vue'), meta: { guest: true } },
    { path: '/register', component: () => import('../views/auth/RegisterView.vue'), meta: { guest: true } },
    {
      path: '/',
      component: () => import('../layouts/MainLayout.vue'),
      children: [
        { path: '', redirect: USER_HOME },
        { path: '/market', component: () => import('../views/market/MarketplaceHomeView.vue') },
        { path: '/ai/publish-assist', component: () => import('../views/ai/PublishAssistView.vue') },
        { path: '/products/publish', component: () => import('../views/product/ProductPublishView.vue') },
        { path: '/products', component: () => import('../views/product/ProductCatalogView.vue') },
        { path: '/products/:productId(\\d+)', component: () => import('../views/product/ProductDetailView.vue') },
        { path: '/favorites', component: () => import('../views/favorite/FavoriteListView.vue') },
        { path: '/orders', component: () => import('../views/order/OrderCenterView.vue') },
        { path: '/ai/rag-chat', component: () => import('../views/ai/RagChatView.vue') },
        { path: '/ai/knowledge', component: () => import('../views/ai/KnowledgeView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/retrieval-debug', component: () => import('../views/ai/RetrievalDebugView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/memory-debug', component: () => import('../views/ai/MemoryDebugView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/react-agent', component: () => import('../views/ai/ReactAgentView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/graph-agent', component: () => import('../views/ai/GraphAgentView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/eval-dashboard', component: () => import('../views/ai/EvalDashboardView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/harness', component: () => import('../views/ai/HarnessDebugView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/storage-consistency', component: () => import('../views/ai/StorageConsistencyView.vue'), meta: { requiresInternal: true } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()
  if (!to.meta.guest && !authStore.isLoggedIn) return '/login'
  if (to.meta.guest && authStore.isLoggedIn) return USER_HOME
  if (to.meta.requiresInternal && !authStore.isInternal) return USER_HOME
  return true
})

export default router
