<script setup lang="ts">
import { Clock3, Heart, MapPin, PackageOpen } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import type { ProductListItem } from '../types/market'

const props = defineProps<{
  product: ProductListItem
  favoritePending?: boolean
}>()

const imageFailed = ref(false)
const displayableImageUrl = computed(() => {
  const value = props.product.mainImageUrl?.trim()
  if (!value || value.startsWith('file:')) return ''
  try {
    const url = new URL(value, window.location.origin)
    if (!['http:', 'https:'].includes(url.protocol) || url.hostname === 'example.invalid') return ''
    return url.href
  } catch {
    return ''
  }
})

defineEmits<{
  view: [product: ProductListItem]
  favorite: [product: ProductListItem]
}>()

function price(value: number | string) {
  const numeric = Number(value)
  return Number.isFinite(numeric)
    ? numeric.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
    : value
}

function conditionText(level?: number) {
  if (!level) return '成色待补充'
  if (level >= 9) return '近全新'
  if (level >= 7) return `${level} 成新`
  if (level >= 5) return '正常使用痕迹'
  return '明显使用痕迹'
}

function relativeTime(value?: string) {
  if (!value) return '最近发布'
  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) return '最近发布'
  const minutes = Math.max(1, Math.floor((Date.now() - timestamp) / 60000))
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return new Date(value).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}
</script>

<template>
  <article class="market-product-card" @click="$emit('view', product)">
    <div class="market-product-image">
      <img
        v-if="displayableImageUrl && !imageFailed"
        :src="displayableImageUrl"
        :alt="product.title"
        loading="lazy"
        @error="imageFailed = true"
      />
      <div v-else class="market-image-empty">
        <PackageOpen :size="28" aria-hidden="true" />
        <span>等待卖家补图</span>
      </div>
      <span class="condition-badge">{{ conditionText(product.conditionLevel) }}</span>
      <button
        class="icon-btn favorite-btn"
        type="button"
        :disabled="favoritePending"
        title="收藏商品"
        aria-label="收藏商品"
        @click.stop="$emit('favorite', product)"
      >
        <Heart :size="18" aria-hidden="true" />
      </button>
    </div>

    <div class="market-product-body">
      <h3>{{ product.title }}</h3>
      <div class="price-line">
        <span class="currency">¥</span>
        <strong>{{ price(product.price) }}</strong>
      </div>
      <div class="product-meta">
        <span><MapPin :size="14" aria-hidden="true" />{{ product.locationText || '支持协商交易地点' }}</span>
        <span><Clock3 :size="14" aria-hidden="true" />{{ relativeTime(product.publishTime) }}</span>
      </div>
      <div class="product-interest">
        <span>{{ product.viewCount ?? 0 }} 人看过</span>
        <span>{{ product.favoriteCount ?? 0 }} 人想要</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.market-product-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
  min-width: 0;
  overflow: hidden;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.market-product-card:hover {
  border-color: rgba(25, 106, 78, 0.42);
  box-shadow: 0 14px 32px rgba(31, 45, 39, 0.11);
  transform: translateY(-2px);
}

.market-product-image {
  aspect-ratio: 4 / 3;
  background: #eef2ef;
  overflow: hidden;
  position: relative;
}

.market-product-image img {
  height: 100%;
  object-fit: cover;
  transition: transform 220ms ease;
  width: 100%;
}

.market-product-card:hover img {
  transform: scale(1.025);
}

.market-image-empty {
  align-content: center;
  color: var(--ink-500);
  display: grid;
  gap: 8px;
  height: 100%;
  justify-items: center;
  width: 100%;
}

.market-image-empty span {
  font-size: 12px;
}

.condition-badge {
  background: rgba(19, 31, 26, 0.82);
  border-radius: 4px;
  bottom: 10px;
  color: #fff;
  font-size: 12px;
  left: 10px;
  padding: 5px 8px;
  position: absolute;
}

.favorite-btn {
  background: rgba(255, 255, 255, 0.94);
  position: absolute;
  right: 10px;
  top: 10px;
}

.favorite-btn:hover {
  background: #fff2ef;
  color: var(--accent-warm);
}

.market-product-body {
  display: grid;
  gap: 9px;
  padding: 13px 14px 14px;
}

.market-product-body h3 {
  display: -webkit-box;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.45;
  margin: 0;
  min-height: 44px;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.price-line {
  align-items: baseline;
  color: var(--accent-warm);
  display: flex;
  gap: 2px;
}

.price-line strong {
  font-size: 23px;
}

.currency {
  font-size: 13px;
  font-weight: 800;
}

.product-meta {
  align-items: center;
  color: var(--ink-500);
  display: flex;
  font-size: 12px;
  gap: 12px;
  justify-content: space-between;
}

.product-meta span {
  align-items: center;
  display: inline-flex;
  gap: 4px;
  min-width: 0;
}

.product-meta span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-interest {
  border-top: 1px solid var(--line);
  color: var(--ink-500);
  display: flex;
  font-size: 11px;
  justify-content: space-between;
  padding-top: 9px;
}
</style>
