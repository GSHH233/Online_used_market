<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, BadgeCheck, Camera, Gamepad2, Headphones, Laptop, Search, ShieldCheck, Smartphone } from 'lucide-vue-next'
import ProductCard from '../../components/ProductCard.vue'
import { favoriteApi } from '../../api/modules/favorite'
import { productApi } from '../../api/modules/product'
import type { ProductListItem } from '../../types/market'

const router = useRouter()
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const favoritePendingId = ref<number | null>(null)
const favoriteNotice = ref('')
const result = ref<any>(null)

const products = computed<ProductListItem[]>(() => result.value?.records || result.value?.data?.records || [])

const categories = [
  { label: '手机数码', keyword: '手机', icon: Smartphone },
  { label: '电脑办公', keyword: '电脑', icon: Laptop },
  { label: '摄影器材', keyword: '相机', icon: Camera },
  { label: '游戏娱乐', keyword: '游戏', icon: Gamepad2 },
  { label: '影音耳机', keyword: '耳机', icon: Headphones },
]

async function loadProducts() {
  loading.value = true
  error.value = ''
  try {
    result.value = await productApi.queryProduct({
      status: 1,
      pageNum: 1,
      pageSize: 8,
      sortBy: 'publishTime',
      sortDirection: 'desc',
    })
  } catch (e: any) {
    error.value = e?.message || '暂时无法加载商品'
  } finally {
    loading.value = false
  }
}

function search(nextKeyword = keyword.value) {
  router.push({ path: '/products', query: nextKeyword.trim() ? { keyword: nextKeyword.trim() } : {} })
}

function viewProduct(product: ProductListItem) {
  router.push(`/products/${product.id}`)
}

async function favorite(product: ProductListItem) {
  favoritePendingId.value = product.id
  favoriteNotice.value = ''
  try {
    await favoriteApi.addFavorite(product.id)
    favoriteNotice.value = `已收藏「${product.title}」`
  } catch (e: any) {
    favoriteNotice.value = e?.message || '收藏失败'
  } finally {
    favoritePendingId.value = null
  }
}

onMounted(loadProducts)
</script>

<template>
  <div class="market-home">
    <section class="market-hero">
      <div class="hero-content">
        <p class="hero-kicker">让好东西继续被需要</p>
        <h1>淘到合适的，也卖掉闲置的</h1>
        <p>真实描述、清楚成色、交易过程可跟踪。发布前还可以让 AI 帮你检查风险。</p>
        <form class="hero-search" @submit.prevent="search()">
          <Search :size="21" aria-hidden="true" />
          <input v-model="keyword" aria-label="搜索二手商品" placeholder="搜索手机、相机、显卡、耳机..." />
          <button class="search-submit" type="submit">搜索</button>
        </form>
        <div class="popular-searches">
          <span>大家在搜</span>
          <button v-for="item in ['iPhone', 'RTX 显卡', '相机', 'Switch']" :key="item" type="button" @click="search(item)">
            {{ item }}
          </button>
        </div>
      </div>
    </section>

    <section class="market-band category-band">
      <div class="section-heading">
        <div>
          <p class="eyebrow">快速发现</p>
          <h2>从常逛分类开始</h2>
        </div>
        <button class="text-action" type="button" @click="search()">
          全部商品 <ArrowRight :size="17" aria-hidden="true" />
        </button>
      </div>
      <div class="category-grid">
        <button v-for="item in categories" :key="item.label" type="button" @click="search(item.keyword)">
          <component :is="item.icon" :size="24" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </button>
      </div>
    </section>

    <section class="trust-strip" aria-label="交易保障">
      <div><BadgeCheck :size="22" aria-hidden="true" /><span><strong>信息清楚</strong>成色、图片和价格集中展示</span></div>
      <div><ShieldCheck :size="22" aria-hidden="true" /><span><strong>发布前检查</strong>AI 辅助识别描述风险</span></div>
      <div><ArrowRight :size="22" aria-hidden="true" /><span><strong>进度可追踪</strong>买卖双方查看订单状态</span></div>
    </section>

    <section class="market-band">
      <div class="section-heading">
        <div>
          <p class="eyebrow">刚刚上新</p>
          <h2>新鲜闲置，慢慢挑</h2>
        </div>
        <button class="text-action" type="button" @click="router.push('/products')">
          查看更多 <ArrowRight :size="17" aria-hidden="true" />
        </button>
      </div>

      <p v-if="favoriteNotice" class="market-notice">{{ favoriteNotice }}</p>
      <p v-if="error" class="error">{{ error }}</p>

      <div v-if="loading" class="market-product-grid">
        <div v-for="index in 8" :key="index" class="product-skeleton" />
      </div>
      <div v-else-if="products.length" class="market-product-grid">
        <ProductCard
          v-for="product in products"
          :key="product.id"
          :product="product"
          :favorite-pending="favoritePendingId === product.id"
          @view="viewProduct"
          @favorite="favorite"
        />
      </div>
      <div v-else class="market-empty">
        <h3>还没有上新的商品</h3>
        <p>成为第一个发布闲置的人，让它重新发挥价值。</p>
        <button class="primary-btn" type="button" @click="router.push('/products/publish')">发布商品</button>
      </div>
    </section>

    <section class="seller-cta">
      <div>
        <p class="eyebrow">卖出闲置</p>
        <h2>三分钟写清商品，发布前再检查一次</h2>
        <p>填写成色、配件、瑕疵和凭证，AI 会辅助检查风险与优化表达。</p>
      </div>
      <div class="seller-actions">
        <button class="primary-btn" type="button" @click="router.push('/products/publish')">开始发布</button>
        <button class="secondary-btn" type="button" @click="router.push('/ai/publish-assist')">先用 AI 助手</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.market-home {
  display: grid;
  gap: 28px;
}

.market-hero {
  align-items: center;
  background: url('https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da?auto=format&fit=crop&w=1800&q=88') center 46% / cover;
  color: #fff;
  display: flex;
  min-height: 430px;
  overflow: hidden;
  padding: 44px clamp(24px, 5vw, 68px);
  position: relative;
}

.market-hero::before {
  background: rgba(17, 31, 25, 0.64);
  content: '';
  inset: 0;
  position: absolute;
}

.hero-content {
  max-width: 760px;
  position: relative;
  z-index: 1;
}

.hero-kicker {
  color: #b8e0cf;
  font-size: 14px;
  font-weight: 800;
  margin: 0 0 12px;
}

.hero-content h1 {
  font-size: 48px;
  line-height: 1.1;
  margin: 0;
  max-width: 760px;
}

.hero-content > p:not(.hero-kicker) {
  color: rgba(255, 255, 255, 0.84);
  font-size: 16px;
  line-height: 1.7;
  margin: 18px 0 24px;
  max-width: 560px;
}

.hero-search {
  align-items: center;
  background: #fff;
  border-radius: 8px;
  color: var(--ink-500);
  display: grid;
  gap: 10px;
  grid-template-columns: auto 1fr auto;
  padding: 7px 7px 7px 15px;
}

.hero-search input {
  border: 0;
  outline: 0;
  padding: 11px 0;
}

.search-submit {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
  min-width: 82px;
}

.popular-searches {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 7px;
  margin-top: 13px;
}

.popular-searches span {
  color: rgba(255, 255, 255, 0.66);
}

.popular-searches button {
  background: rgba(255, 255, 255, 0.11);
  border-color: rgba(255, 255, 255, 0.18);
  color: #fff;
  padding: 6px 9px;
}

.market-band {
  display: grid;
  gap: 16px;
}

.section-heading {
  align-items: end;
  display: flex;
  justify-content: space-between;
}

.section-heading h2 {
  font-size: 25px;
  margin: 3px 0 0;
}

.category-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.category-grid button {
  align-items: center;
  background: #fff;
  border-color: var(--line);
  display: flex;
  font-weight: 700;
  gap: 11px;
  justify-content: center;
  min-height: 74px;
}

.category-grid button:hover {
  background: var(--accent-soft);
  border-color: rgba(25, 106, 78, 0.35);
  color: var(--accent);
}

.trust-strip {
  background: var(--ink-900);
  color: #fff;
  display: grid;
  gap: 20px;
  grid-template-columns: repeat(3, 1fr);
  padding: 22px 26px;
}

.trust-strip > div {
  align-items: center;
  display: flex;
  gap: 12px;
}

.trust-strip svg {
  color: #8fd0b3;
  flex: 0 0 auto;
}

.trust-strip span {
  color: rgba(255, 255, 255, 0.68);
  display: grid;
  font-size: 12px;
  gap: 2px;
}

.trust-strip strong {
  color: #fff;
  font-size: 14px;
}

.market-notice {
  background: var(--accent-soft);
  color: var(--accent);
  margin: 0;
  padding: 10px 12px;
}

.product-skeleton {
  aspect-ratio: 4 / 5;
  animation: pulse 1.3s infinite ease-in-out;
  background: #e8ece9;
  border-radius: 8px;
}

.market-empty {
  background: #fff;
  border: 1px dashed var(--line-strong);
  display: grid;
  gap: 8px;
  justify-items: center;
  padding: 48px 20px;
  text-align: center;
}

.market-empty h3,
.market-empty p {
  margin: 0;
}

.market-empty p {
  color: var(--ink-500);
}

.seller-cta {
  align-items: center;
  background: #eaf3ee;
  display: flex;
  gap: 24px;
  justify-content: space-between;
  padding: 30px 34px;
}

.seller-cta h2 {
  margin: 4px 0 8px;
}

.seller-cta p {
  color: var(--ink-500);
  margin: 0;
}

.seller-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
}

@keyframes pulse {
  50% { opacity: 0.55; }
}

@media (max-width: 820px) {
  .market-hero {
    background-position: 60% center;
    min-height: 500px;
    padding: 34px 20px;
  }

  .hero-content h1 {
    font-size: 38px;
  }

  .category-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .category-grid button:last-child {
    grid-column: 1 / -1;
  }

  .trust-strip {
    grid-template-columns: 1fr;
  }

  .seller-cta,
  .section-heading {
    align-items: flex-start;
    display: grid;
  }

  .seller-actions {
    flex-wrap: wrap;
  }
}
</style>
