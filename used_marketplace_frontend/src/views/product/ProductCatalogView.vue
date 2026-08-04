<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronLeft, ChevronRight, Search, SlidersHorizontal } from 'lucide-vue-next'
import ProductCard from '../../components/ProductCard.vue'
import { favoriteApi } from '../../api/modules/favorite'
import { productApi } from '../../api/modules/product'
import type { ProductListItem } from '../../types/market'

const route = useRoute()
const router = useRouter()
const form = reactive({
  keyword: '',
  minPrice: '',
  maxPrice: '',
  pageNum: 1,
  pageSize: 12,
  sortBy: 'publishTime',
  sortDirection: 'desc',
})

const loading = ref(false)
const error = ref('')
const notice = ref('')
const filterOpen = ref(false)
const favoritePendingId = ref<number | null>(null)
const result = ref<any>(null)

const products = computed<ProductListItem[]>(() => result.value?.records || result.value?.data?.records || [])
const total = computed(() => Number(result.value?.total || result.value?.data?.total || 0))
const pages = computed(() => Number(result.value?.pages || result.value?.data?.pages || 0))
const categories = ['全部', '手机', '电脑', '相机', '显卡', '耳机', '游戏机', '生活闲置']

function buildParams() {
  return {
    keyword: form.keyword || undefined,
    minPrice: form.minPrice || undefined,
    maxPrice: form.maxPrice || undefined,
    status: 1,
    pageNum: form.pageNum,
    pageSize: form.pageSize,
    sortBy: form.sortBy,
    sortDirection: form.sortDirection,
  }
}

async function load(updateUrl = true) {
  loading.value = true
  error.value = ''
  try {
    result.value = await productApi.queryProduct(buildParams())
    if (updateUrl) {
      await router.replace({
        query: {
          ...(form.keyword ? { keyword: form.keyword } : {}),
          ...(form.minPrice ? { minPrice: form.minPrice } : {}),
          ...(form.maxPrice ? { maxPrice: form.maxPrice } : {}),
          ...(form.pageNum > 1 ? { page: String(form.pageNum) } : {}),
        },
      })
    }
  } catch (e: any) {
    error.value = e?.message || '获取商品失败'
  } finally {
    loading.value = false
  }
}

function search() {
  form.pageNum = 1
  load()
}

function chooseCategory(label: string) {
  form.keyword = label === '全部' ? '' : label
  search()
}

function reset() {
  form.keyword = ''
  form.minPrice = ''
  form.maxPrice = ''
  form.pageNum = 1
  form.sortBy = 'publishTime'
  form.sortDirection = 'desc'
  filterOpen.value = false
  load()
}

function changeSort(value: string) {
  const [sortBy, sortDirection] = value.split(':')
  form.sortBy = sortBy
  form.sortDirection = sortDirection
  form.pageNum = 1
  load()
}

function prevPage() {
  if (form.pageNum <= 1) return
  form.pageNum -= 1
  load()
}

function nextPage() {
  if (pages.value && form.pageNum >= pages.value) return
  form.pageNum += 1
  load()
}

function viewProduct(product: ProductListItem) {
  router.push(`/products/${product.id}`)
}

async function favorite(product: ProductListItem) {
  favoritePendingId.value = product.id
  notice.value = ''
  try {
    await favoriteApi.addFavorite(product.id)
    notice.value = `已收藏「${product.title}」`
  } catch (e: any) {
    notice.value = e?.message || '收藏失败'
  } finally {
    favoritePendingId.value = null
  }
}

onMounted(() => {
  form.keyword = String(route.query.keyword || '')
  form.minPrice = String(route.query.minPrice || '')
  form.maxPrice = String(route.query.maxPrice || '')
  form.pageNum = Math.max(1, Number(route.query.page || 1))
  load(false)
})

watch(
  () => route.query.keyword,
  (keyword) => {
    const next = String(keyword || '')
    if (next !== form.keyword) {
      form.keyword = next
      form.pageNum = 1
      load(false)
    }
  },
)
</script>

<template>
  <div class="catalog-page">
    <section class="catalog-header">
      <div>
        <p class="eyebrow">发现好物</p>
        <h1>{{ form.keyword ? `搜索“${form.keyword}”` : '逛逛大家的闲置' }}</h1>
        <p>{{ total ? `找到 ${total} 件在售商品` : '从真实图片、成色和价格开始比较。' }}</p>
      </div>
      <form class="catalog-search" @submit.prevent="search">
        <Search :size="19" aria-hidden="true" />
        <input v-model="form.keyword" aria-label="搜索商品" placeholder="搜索你想要的二手商品" />
        <button class="primary-btn" type="submit">搜索</button>
      </form>
    </section>

    <nav class="category-tabs" aria-label="商品分类">
      <button
        v-for="label in categories"
        :key="label"
        :class="{ active: label === '全部' ? !form.keyword : form.keyword === label }"
        type="button"
        @click="chooseCategory(label)"
      >
        {{ label }}
      </button>
    </nav>

    <section class="catalog-toolbar">
      <div class="toolbar-left">
        <button class="filter-toggle" type="button" @click="filterOpen = !filterOpen">
          <SlidersHorizontal :size="17" aria-hidden="true" />
          价格筛选
        </button>
        <span v-if="form.minPrice || form.maxPrice" class="active-filter">
          ¥{{ form.minPrice || 0 }} - ¥{{ form.maxPrice || '不限' }}
        </span>
      </div>
      <select
        :value="`${form.sortBy}:${form.sortDirection}`"
        aria-label="商品排序"
        @change="changeSort(($event.target as HTMLSelectElement).value)"
      >
        <option value="publishTime:desc">最新发布</option>
        <option value="price:asc">价格从低到高</option>
        <option value="price:desc">价格从高到低</option>
      </select>
    </section>

    <section v-if="filterOpen" class="price-filter">
      <label>最低价<input v-model="form.minPrice" type="number" min="0" placeholder="0" /></label>
      <span>至</span>
      <label>最高价<input v-model="form.maxPrice" type="number" min="0" placeholder="不限" /></label>
      <button class="primary-btn" type="button" @click="search">应用</button>
      <button class="secondary-btn" type="button" @click="reset">清除</button>
    </section>

    <p v-if="notice" class="market-notice">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="loading" class="market-product-grid">
      <div v-for="index in 12" :key="index" class="product-skeleton" />
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
    <section v-else class="catalog-empty">
      <h2>没有找到匹配商品</h2>
      <p>换个关键词，或清除价格条件再看看。</p>
      <button class="primary-btn" type="button" @click="reset">查看全部商品</button>
    </section>

    <nav v-if="products.length" class="market-pagination" aria-label="商品分页">
      <button class="icon-btn" type="button" :disabled="loading || form.pageNum <= 1" title="上一页" aria-label="上一页" @click="prevPage">
        <ChevronLeft :size="19" aria-hidden="true" />
      </button>
      <span>第 {{ form.pageNum }} / {{ pages || 1 }} 页</span>
      <button class="icon-btn" type="button" :disabled="loading || (pages > 0 && form.pageNum >= pages)" title="下一页" aria-label="下一页" @click="nextPage">
        <ChevronRight :size="19" aria-hidden="true" />
      </button>
    </nav>
  </div>
</template>

<style scoped>
.catalog-page {
  display: grid;
  gap: 18px;
}

.catalog-header {
  align-items: end;
  display: grid;
  gap: 28px;
  grid-template-columns: minmax(0, 0.78fr) minmax(340px, 1.22fr);
  padding: 18px 0 8px;
}

.catalog-header h1 {
  font-size: 40px;
  margin: 4px 0 8px;
}

.catalog-header p {
  color: var(--ink-500);
  margin: 0;
}

.catalog-search {
  align-items: center;
  background: #fff;
  border: 1px solid var(--line-strong);
  display: grid;
  gap: 10px;
  grid-template-columns: auto 1fr auto;
  padding: 6px 6px 6px 14px;
}

.catalog-search input {
  border: 0;
  outline: 0;
}

.category-tabs {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.category-tabs button {
  background: #fff;
  border-color: var(--line);
  flex: 0 0 auto;
  padding: 8px 13px;
}

.category-tabs button.active {
  background: var(--ink-900);
  border-color: var(--ink-900);
  color: #fff;
}

.catalog-toolbar {
  align-items: center;
  border-block: 1px solid var(--line);
  display: flex;
  justify-content: space-between;
  padding: 11px 0;
}

.toolbar-left,
.filter-toggle {
  align-items: center;
  display: flex;
  gap: 8px;
}

.filter-toggle {
  background: transparent;
  border: 0;
  padding-left: 0;
}

.filter-toggle:hover {
  background: transparent;
  color: var(--accent);
}

.active-filter {
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  padding: 6px 8px;
}

.catalog-toolbar select {
  min-width: 150px;
  width: auto;
}

.price-filter {
  align-items: end;
  background: #fff;
  border: 1px solid var(--line);
  display: grid;
  gap: 10px;
  grid-template-columns: 160px auto 160px auto auto;
  padding: 14px;
}

.price-filter label {
  margin: 0;
}

.price-filter > span {
  align-self: center;
  color: var(--ink-500);
}

.catalog-empty {
  border: 1px dashed var(--line-strong);
  display: grid;
  gap: 8px;
  justify-items: center;
  padding: 70px 20px;
  text-align: center;
}

.catalog-empty h2,
.catalog-empty p {
  margin: 0;
}

.catalog-empty p {
  color: var(--ink-500);
}

.market-pagination {
  align-items: center;
  display: flex;
  gap: 14px;
  justify-content: center;
  padding-top: 8px;
}

.market-pagination span {
  color: var(--ink-500);
  font-size: 13px;
}

@media (max-width: 760px) {
  .catalog-header {
    align-items: stretch;
    grid-template-columns: 1fr;
  }

  .catalog-header h1 {
    font-size: 30px;
  }

  .price-filter {
    grid-template-columns: 1fr 1fr;
  }

  .price-filter > span {
    display: none;
  }
}
</style>
