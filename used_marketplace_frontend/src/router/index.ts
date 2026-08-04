import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const USER_HOME = '/market'
const AI_DEBUG_ENABLED = import.meta.env.VITE_ENABLE_AI_DEBUG === 'true'

function redirectTarget(value: unknown) {
  const target = Array.isArray(value) ? value[0] : value
  if (typeof target !== 'string') return USER_HOME
  if (!target.startsWith('/') || target.startsWith('//') || target.startsWith('/login')) return USER_HOME
  return target
}

const debugRoutes = AI_DEBUG_ENABLED
  ? [
      {
        path: '/ai/react-agent',
        component: () => import('../views/ai/ReactAgentView.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: '/ai/graph-agent',
        component: () => import('../views/ai/GraphAgentView.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: '/ai/harness',
        component: () => import('../views/ai/HarnessDebugView.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: '/ai/memory-debug',
        component: () => import('../views/ai/MemoryDebugView.vue'),
        meta: { requiresAdmin: true },
      },
    ]
  : []

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
        { path: '/ai/publish-assist', redirect: { path: '/products/publish', query: { ai: '1' } } },
        { path: '/products/publish', component: () => import('../views/product/ProductPublishView.vue') },
        { path: '/products', component: () => import('../views/product/ProductCatalogView.vue') },
        { path: '/products/:productId(\\d+)', component: () => import('../views/product/ProductDetailView.vue') },
        { path: '/favorites', component: () => import('../views/favorite/FavoriteListView.vue') },
        { path: '/orders', component: () => import('../views/order/OrderCenterView.vue') },
        { path: '/ai/rag-chat', component: () => import('../views/ai/RagChatView.vue') },
        { path: '/ai/knowledge', component: () => import('../views/ai/KnowledgeView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/knowledge-gaps', redirect: { path: '/ai/knowledge', query: { tab: 'gaps' } } },
        { path: '/ai/retrieval-debug', redirect: { path: '/ai/knowledge', query: { tab: 'retrieval' } } },
        { path: '/ai/investigate', component: () => import('../views/ai/AgentInvestigationView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/runtime', component: () => import('../views/ai/AgentRunListView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/runtime/:runId', component: () => import('../views/ai/AgentRunDetailView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/aiops', redirect: { path: '/ai/investigate', query: { tab: 'operations' } } },
        { path: '/ai/publish-feedback', redirect: { path: '/ai/eval-dashboard', query: { section: 'publish' } } },
        { path: '/ai/eval-dashboard', component: () => import('../views/ai/EvalDashboardView.vue'), meta: { requiresInternal: true } },
        { path: '/ai/storage-consistency', redirect: { path: '/ai/knowledge', query: { tab: 'storage' } } },
        ...debugRoutes,
        { path: '/:pathMatch(.*)*', redirect: USER_HOME },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()
  if (!to.meta.guest && !authStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guest && authStore.isLoggedIn) return redirectTarget(to.query.redirect)
  if (to.meta.requiresInternal && !authStore.isInternal) return USER_HOME
  if (to.meta.requiresAdmin && !authStore.isAdmin) return '/ai/eval-dashboard'
  return true
})

export default router
