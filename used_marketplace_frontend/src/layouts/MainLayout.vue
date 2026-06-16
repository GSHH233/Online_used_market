<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bot,
  Boxes,
  ChevronDown,
  Heart,
  Home,
  LogOut,
  Menu,
  PackagePlus,
  Search,
  ShoppingBag,
  Store,
  X,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

type MenuItem = {
  path: string
  label: string
  icon?: any
}

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const mobileOpen = ref(false)
const searchKeyword = ref('')

const userMenus: MenuItem[] = [
  { path: '/market', label: '首页', icon: Home },
  { path: '/products', label: '逛商品', icon: Search },
  { path: '/products/publish', label: '发布', icon: PackagePlus },
  { path: '/orders', label: '订单', icon: ShoppingBag },
]

const internalMenus: MenuItem[] = [
  { path: '/ai/eval-dashboard', label: '评测看板' },
  { path: '/ai/knowledge', label: '知识库' },
  { path: '/ai/react-agent', label: 'ReAct' },
  { path: '/ai/graph-agent', label: 'Graph' },
  { path: '/ai/storage-consistency', label: '存储一致性' },
]

const roleLabel = computed(() => {
  if (auth.normalizedRole === 'ADMIN') return '管理员'
  if (auth.normalizedRole === 'INTERNAL') return '内部用户'
  return '普通用户'
})

const internalRoute = computed(() =>
  internalMenus.some((item) => route.path === item.path),
)

function isActive(path: string) {
  if (path === '/products') {
    return route.path === '/products' || /^\/products\/\d+$/.test(route.path)
  }
  return route.path === path
}

function go(path: string) {
  mobileOpen.value = false
  router.push(path)
}

function submitSearch() {
  mobileOpen.value = false
  router.push({
    path: '/products',
    query: searchKeyword.value.trim() ? { keyword: searchKeyword.value.trim() } : {},
  })
}

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-frame">
    <header class="market-header">
      <div class="header-inner">
        <button class="brand" type="button" @click="go('/market')">
          <span class="brand-mark"><Store :size="21" aria-hidden="true" /></span>
          <span>回血集市</span>
        </button>

        <nav class="desktop-nav" aria-label="商城导航">
          <button
            v-for="item in userMenus"
            :key="item.path"
            :class="{ active: isActive(item.path) }"
            type="button"
            @click="go(item.path)"
          >
            {{ item.label }}
          </button>
        </nav>

        <form class="header-search" @submit.prevent="submitSearch">
          <Search :size="17" aria-hidden="true" />
          <input v-model="searchKeyword" aria-label="全站搜索" placeholder="搜手机、相机、显卡" />
        </form>

        <div class="header-actions">
          <button class="icon-btn" type="button" title="我的收藏" aria-label="我的收藏" @click="go('/favorites')">
            <Heart :size="19" aria-hidden="true" />
          </button>
          <button class="icon-btn" type="button" title="交易问答" aria-label="交易问答" @click="go('/ai/rag-chat')">
            <Bot :size="19" aria-hidden="true" />
          </button>
          <button v-if="auth.isInternal" class="console-btn" type="button" @click="go('/ai/eval-dashboard')">
            <Boxes :size="17" aria-hidden="true" /> 运营后台
          </button>
          <div class="account-menu">
            <button type="button">
              <span class="avatar">{{ roleLabel.slice(0, 1) }}</span>
              <span>{{ roleLabel }}</span>
              <ChevronDown :size="15" aria-hidden="true" />
            </button>
            <div class="account-popover">
              <span>{{ auth.normalizedRole }}</span>
              <button type="button" @click="logout"><LogOut :size="16" aria-hidden="true" />退出登录</button>
            </div>
          </div>
          <button class="mobile-menu-btn icon-btn" type="button" :aria-label="mobileOpen ? '关闭菜单' : '打开菜单'" @click="mobileOpen = !mobileOpen">
            <X v-if="mobileOpen" :size="21" aria-hidden="true" />
            <Menu v-else :size="21" aria-hidden="true" />
          </button>
        </div>
      </div>

      <div v-if="mobileOpen" class="mobile-menu">
        <form class="mobile-search" @submit.prevent="submitSearch">
          <Search :size="18" aria-hidden="true" />
          <input v-model="searchKeyword" aria-label="搜索商品" placeholder="搜索二手好物" />
          <button type="submit">搜索</button>
        </form>
        <button v-for="item in userMenus" :key="item.path" type="button" @click="go(item.path)">
          <component :is="item.icon" :size="18" aria-hidden="true" />{{ item.label }}
        </button>
        <button type="button" @click="go('/favorites')"><Heart :size="18" />我的收藏</button>
        <button type="button" @click="go('/ai/rag-chat')"><Bot :size="18" />交易问答</button>
        <button v-if="auth.isInternal" type="button" @click="go('/ai/eval-dashboard')"><Boxes :size="18" />运营后台</button>
      </div>
    </header>

    <nav v-if="auth.isInternal && internalRoute" class="internal-nav" aria-label="内部控制台导航">
      <div class="internal-nav-inner">
        <span>内部工具</span>
        <button
          v-for="item in internalMenus"
          :key="item.path"
          :class="{ active: route.path === item.path }"
          type="button"
          @click="go(item.path)"
        >
          {{ item.label }}
        </button>
      </div>
    </nav>

    <main class="market-main">
      <router-view />
    </main>

    <nav class="mobile-bottom-nav" aria-label="移动端主导航">
      <button
        v-for="item in userMenus"
        :key="item.path"
        :class="{ active: isActive(item.path) }"
        type="button"
        @click="go(item.path)"
      >
        <component :is="item.icon" :size="20" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.app-frame {
  min-height: 100vh;
}

.market-header {
  background: rgba(251, 249, 242, 0.9);
  backdrop-filter: blur(18px);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 40;
}

.header-inner {
  align-items: center;
  display: grid;
  gap: 24px;
  grid-template-columns: auto auto minmax(180px, 1fr) auto;
  margin: 0 auto;
  max-width: 1440px;
  min-height: 66px;
  padding: 0 28px;
}

.brand {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--ink-900);
  display: inline-flex;
  font-size: 19px;
  font-weight: 850;
  gap: 9px;
  padding: 0;
}

.brand:hover {
  background: transparent;
  color: var(--accent);
}

.brand-mark {
  align-items: center;
  background: var(--ink-900);
  border-radius: 50%;
  color: #fff;
  display: inline-flex;
  height: 34px;
  justify-content: center;
  width: 34px;
}

.desktop-nav {
  align-items: center;
  display: flex;
  gap: 2px;
}

.desktop-nav button {
  background: transparent;
  border: 0;
  color: var(--ink-700);
  font-weight: 650;
  padding: 10px 11px;
}

.desktop-nav button:hover,
.desktop-nav button.active {
  background: transparent;
  color: var(--accent);
}

.desktop-nav button.active {
  box-shadow: inset 0 -2px var(--accent);
}

.header-search {
  align-items: center;
  background: rgba(255, 255, 255, 0.68);
  border: 1px solid transparent;
  border-radius: 999px;
  display: grid;
  gap: 8px;
  grid-template-columns: auto 1fr;
  justify-self: end;
  max-width: 320px;
  padding: 0 12px;
  width: 100%;
}

.header-search:focus-within {
  background: #fff;
  border-color: rgba(41, 75, 61, 0.3);
}

.header-search input {
  background: transparent;
  border: 0;
  outline: 0;
  padding: 10px 0;
}

.header-actions {
  align-items: center;
  display: flex;
  gap: 7px;
}

.console-btn {
  align-items: center;
  background: var(--ink-900);
  color: #fff;
  display: inline-flex;
  gap: 6px;
  white-space: nowrap;
}

.account-menu {
  position: relative;
}

.account-menu > button {
  align-items: center;
  background: transparent;
  border: 0;
  display: flex;
  gap: 7px;
  padding: 5px;
}

.avatar {
  align-items: center;
  background: #e9e3d4;
  color: var(--ink-900);
  display: inline-flex;
  font-size: 12px;
  font-weight: 850;
  height: 32px;
  justify-content: center;
  width: 32px;
}

.account-popover {
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: 0 14px 30px rgba(31, 45, 39, 0.13);
  display: none;
  gap: 7px;
  min-width: 150px;
  padding: 10px;
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
}

.account-popover span {
  color: var(--ink-500);
  font-size: 11px;
  padding: 4px 7px;
}

.account-popover button {
  align-items: center;
  border: 0;
  display: flex;
  gap: 7px;
  width: 100%;
}

.account-menu:hover .account-popover,
.account-menu:focus-within .account-popover {
  display: grid;
}

.mobile-menu-btn,
.mobile-menu,
.mobile-bottom-nav {
  display: none;
}

.internal-nav {
  background: var(--ink-900);
  color: #fff;
}

.internal-nav-inner {
  align-items: center;
  display: flex;
  gap: 4px;
  margin: 0 auto;
  max-width: 1440px;
  overflow-x: auto;
  padding: 8px 28px;
}

.internal-nav-inner > span {
  color: #d6caa8;
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 800;
  margin-right: 12px;
}

.internal-nav button {
  background: transparent;
  border: 0;
  color: rgba(255, 255, 255, 0.72);
  flex: 0 0 auto;
  font-size: 12px;
  padding: 7px 9px;
}

.internal-nav button:hover,
.internal-nav button.active {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.market-main {
  margin: 0 auto;
  max-width: 1320px;
  padding: 24px 28px 48px;
}

@media (max-width: 1080px) {
  .header-inner {
    gap: 14px;
    grid-template-columns: auto 1fr auto;
  }

  .desktop-nav {
    display: none;
  }

  .header-search {
    justify-self: stretch;
    max-width: none;
  }
}

@media (max-width: 760px) {
  .header-inner {
    grid-template-columns: 1fr auto;
    min-height: 58px;
    padding: 0 16px;
  }

  .header-search,
  .header-actions > .icon-btn,
  .console-btn,
  .account-menu {
    display: none;
  }

  .mobile-menu-btn {
    display: inline-flex;
  }

  .mobile-menu {
    background: var(--surface);
    border-top: 1px solid var(--line);
    display: grid;
    gap: 4px;
    padding: 12px 16px 18px;
  }

  .mobile-menu > button {
    align-items: center;
    background: transparent;
    border: 0;
    display: flex;
    gap: 9px;
    justify-content: flex-start;
  }

  .mobile-search {
    align-items: center;
    background: rgba(255, 255, 255, 0.72);
    display: grid;
    gap: 8px;
    grid-template-columns: auto 1fr auto;
    margin-bottom: 6px;
    padding-left: 11px;
  }

  .mobile-search input {
    background: transparent;
    border: 0;
  }

  .internal-nav-inner {
    padding-inline: 16px;
  }

  .market-main {
    padding: 18px 16px 88px;
  }

  .mobile-bottom-nav {
    background: rgba(251, 249, 242, 0.96);
    border-top: 1px solid var(--line);
    bottom: 0;
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    left: 0;
    padding: 7px 6px max(7px, env(safe-area-inset-bottom));
    position: fixed;
    right: 0;
    z-index: 35;
  }

  .mobile-bottom-nav button {
    align-items: center;
    background: transparent;
    border: 0;
    color: var(--ink-500);
    display: grid;
    font-size: 10px;
    gap: 3px;
    justify-items: center;
    padding: 4px 2px;
  }

  .mobile-bottom-nav button.active {
    color: var(--accent);
  }
}
</style>
