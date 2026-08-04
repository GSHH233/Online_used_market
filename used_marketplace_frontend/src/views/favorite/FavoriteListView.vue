<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronLeft, ChevronRight, HeartOff, PackageOpen, ShoppingBag } from 'lucide-vue-next'
import { favoriteApi } from '../../api/modules/favorite'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const removingId = ref<number | string | null>(null)
const error = ref('')
const result = ref<any>(null)
const page = reactive({ pageNum: 1, pageSize: 12 })

const items = computed(() => result.value?.records || result.value?.data?.records || [])
const total = computed(() => Number(result.value?.total || result.value?.data?.total || 0))
const pages = computed(() => Number(result.value?.pages || result.value?.data?.pages || 0))

async function load() {
  loading.value = true
  error.value = ''
  try {
    result.value = await favoriteApi.listFavorites(page)
  } catch (e: any) {
    error.value = e?.message || '获取收藏失败'
  } finally {
    loading.value = false
  }
}

async function remove(productId: number | string) {
  removingId.value = productId
  error.value = ''
  try {
    await favoriteApi.removeFavorite(productId)
    await load()
  } catch (e: any) {
    error.value = e?.message || '取消收藏失败'
  } finally {
    removingId.value = null
  }
}

function prev() {
  if (page.pageNum <= 1) return
  page.pageNum -= 1
  load()
}

function next() {
  if (pages.value && page.pageNum >= pages.value) return
  page.pageNum += 1
  load()
}

function productStatusText(status?: number | string) {
  return String(status) === '1' ? '在售' : '已下架'
}

function formatPrice(value?: number | string) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric.toLocaleString('zh-CN') : value || '—'
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '最近收藏'
}

onMounted(load)
</script>

<template>
  <div class="favorite-page">
    <header class="collection-heading">
      <div>
        <p class="eyebrow">我的关注</p>
        <h1>收藏的好物</h1>
        <p>已收藏 {{ total }} 件商品，价格和在售状态以商品详情为准。</p>
      </div>
      <button class="secondary-btn" type="button" @click="router.push('/products')">
        <ShoppingBag :size="18" aria-hidden="true" />
        继续逛逛
      </button>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="loading && !items.length" class="market-product-grid">
      <div v-for="index in 8" :key="index" class="product-skeleton" />
    </div>

    <section v-else-if="items.length" class="favorite-grid">
      <article
        v-for="item in items"
        :key="item.id"
        class="favorite-card"
        tabindex="0"
        @click="router.push(`/products/${item.productId}`)"
        @keydown.enter="router.push(`/products/${item.productId}`)"
      >
        <div class="favorite-cover">
          <img v-if="item.productMainImage" :src="item.productMainImage" :alt="item.productTitle" />
          <div v-else class="favorite-placeholder">
            <PackageOpen :size="30" aria-hidden="true" />
            <span>暂无商品图片</span>
          </div>
          <span :class="['sale-badge', { unavailable: String(item.status) !== '1' }]">
            {{ productStatusText(item.status) }}
          </span>
        </div>
        <div class="favorite-body">
          <h2>{{ item.productTitle }}</h2>
          <p class="favorite-price"><span>¥</span>{{ formatPrice(item.price) }}</p>
          <div class="favorite-footer">
            <span>{{ formatDate(item.createdTime) }} 收藏</span>
            <button
              class="remove-favorite"
              type="button"
              :disabled="removingId === item.productId"
              @click.stop="remove(item.productId)"
            >
              <HeartOff :size="16" aria-hidden="true" />
              取消
            </button>
          </div>
          <span v-if="auth.isInternal" class="internal-product-id">商品 ID {{ item.productId }}</span>
        </div>
      </article>
    </section>

    <section v-else class="collection-empty">
      <div><HeartOff :size="34" aria-hidden="true" /></div>
      <h2>收藏夹还是空的</h2>
      <p>逛到感兴趣的商品时点一下爱心，之后可以回来继续比较。</p>
      <button class="primary-btn" type="button" @click="router.push('/products')">去发现好物</button>
    </section>

    <nav v-if="items.length" class="market-pagination" aria-label="收藏分页">
      <button class="icon-btn" type="button" :disabled="loading || page.pageNum <= 1" title="上一页" aria-label="上一页" @click="prev">
        <ChevronLeft :size="19" aria-hidden="true" />
      </button>
      <span>第 {{ page.pageNum }} / {{ pages || 1 }} 页</span>
      <button class="icon-btn" type="button" :disabled="loading || (pages > 0 && page.pageNum >= pages)" title="下一页" aria-label="下一页" @click="next">
        <ChevronRight :size="19" aria-hidden="true" />
      </button>
    </nav>

    <details v-if="auth.isInternal" class="panel">
      <summary>原始响应</summary>
      <pre>{{ result || '暂无结果' }}</pre>
    </details>
  </div>
</template>

<style scoped>
.favorite-page {
  display: grid;
  gap: 22px;
}

.collection-heading {
  align-items: end;
  display: flex;
  justify-content: space-between;
  padding: 18px 0 8px;
}

.collection-heading h1 {
  font-size: 38px;
  margin: 5px 0 8px;
}

.collection-heading p:last-child {
  color: var(--ink-500);
  margin: 0;
}

.collection-heading .secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
}

details summary {
  cursor: pointer;
  font-weight: 800;
}

.favorite-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.favorite-card {
  background: #fff;
  border: 1px solid var(--line);
  cursor: pointer;
  min-width: 0;
  overflow: hidden;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.favorite-card:hover,
.favorite-card:focus-visible {
  border-color: rgba(25, 106, 78, 0.42);
  box-shadow: 0 12px 28px rgba(31, 45, 39, 0.1);
  outline: 0;
  transform: translateY(-2px);
}

.favorite-cover {
  aspect-ratio: 4 / 3;
  background: #edf1ee;
  position: relative;
}

.favorite-cover img {
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.favorite-placeholder {
  align-content: center;
  color: var(--ink-500);
  display: grid;
  gap: 8px;
  height: 100%;
  justify-items: center;
}

.favorite-placeholder span {
  font-size: 12px;
}

.sale-badge {
  background: rgba(23, 35, 30, 0.84);
  bottom: 10px;
  color: #fff;
  font-size: 12px;
  left: 10px;
  padding: 5px 8px;
  position: absolute;
}

.sale-badge.unavailable {
  background: rgba(108, 124, 116, 0.9);
}

.favorite-body {
  display: grid;
  gap: 9px;
  padding: 14px;
}

.favorite-body h2 {
  font-size: 15px;
  line-height: 1.45;
  margin: 0;
  min-height: 44px;
}

.favorite-price {
  color: var(--accent-warm);
  font-size: 23px;
  font-weight: 800;
  margin: 0;
}

.favorite-price span {
  font-size: 13px;
  margin-right: 2px;
}

.favorite-footer {
  align-items: center;
  border-top: 1px solid var(--line);
  color: var(--ink-500);
  display: flex;
  font-size: 11px;
  justify-content: space-between;
  padding-top: 9px;
}

.remove-favorite {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--ink-500);
  display: inline-flex;
  gap: 4px;
  padding: 4px;
}

.remove-favorite:hover:not(:disabled) {
  border-color: transparent;
  color: var(--accent-warm);
}

.internal-product-id {
  color: var(--ink-500);
  font-size: 11px;
}

.collection-empty {
  align-items: center;
  background: #fff;
  border: 1px solid var(--line);
  display: grid;
  justify-items: center;
  min-height: 360px;
  padding: 42px 20px;
  text-align: center;
}

.collection-empty > div {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 50%;
  color: var(--accent);
  display: flex;
  height: 74px;
  justify-content: center;
  width: 74px;
}

.collection-empty h2 {
  margin: 16px 0 5px;
}

.collection-empty p {
  color: var(--ink-500);
  margin: 0 0 18px;
  max-width: 430px;
}

@media (max-width: 980px) {
  .favorite-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .collection-heading {
    align-items: start;
    gap: 16px;
  }

  .collection-heading h1 {
    font-size: 30px;
  }

  .favorite-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 420px) {
  .collection-heading {
    display: grid;
  }

  .collection-heading .secondary-btn {
    justify-content: center;
  }
}
</style>
