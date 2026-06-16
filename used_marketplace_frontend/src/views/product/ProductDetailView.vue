<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  BadgeCheck,
  CalendarDays,
  Eye,
  Heart,
  MapPin,
  MessageCircleQuestion,
  PackageCheck,
  ShieldCheck,
  ShoppingBag,
  UserRound,
} from 'lucide-vue-next'
import { favoriteApi } from '../../api/modules/favorite'
import { orderApi } from '../../api/modules/order'
import { productApi } from '../../api/modules/product'
import { useAuthStore } from '../../stores/auth'
import type { ProductDetail } from '../../types/market'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const product = ref<ProductDetail | null>(null)
const loading = ref(true)
const actionLoading = ref(false)
const error = ref('')
const notice = ref('')
const activeImage = ref(0)
const remark = ref('')

const productId = computed(() => Number(route.params.productId))
const images = computed(() => product.value?.imageUrls?.filter(Boolean) || [])
const currentImage = computed(() => images.value[activeImage.value] || '')
const originalPriceVisible = computed(() => {
  const original = Number(product.value?.originalPrice || 0)
  const current = Number(product.value?.price || 0)
  return original > current
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    product.value = await productApi.getProductDetail(productId.value)
  } catch (e: any) {
    error.value = e?.message || '商品信息加载失败'
  } finally {
    loading.value = false
  }
}

async function favorite() {
  if (!product.value) return
  actionLoading.value = true
  notice.value = ''
  try {
    await favoriteApi.addFavorite(product.value.id)
    notice.value = '已加入收藏，可以在“我的收藏”继续比较'
  } catch (e: any) {
    notice.value = e?.message || '收藏失败'
  } finally {
    actionLoading.value = false
  }
}

async function buy() {
  if (!product.value) return
  actionLoading.value = true
  notice.value = ''
  try {
    await orderApi.createOrder({
      productId: product.value.id,
      remark: remark.value.trim() || undefined,
    })
    await router.push({ path: '/orders', query: { created: '1' } })
  } catch (e: any) {
    notice.value = e?.message || '创建订单失败'
  } finally {
    actionLoading.value = false
  }
}

function price(value?: number | string) {
  const numeric = Number(value)
  return Number.isFinite(numeric)
    ? numeric.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
    : '—'
}

function conditionText(level?: number) {
  if (!level) return '卖家未填写'
  if (level >= 9) return '近全新'
  if (level >= 7) return `${level} 成新，正常使用`
  if (level >= 5) return '有正常使用痕迹'
  return '有明显使用痕迹'
}

function statusText(status?: number | string) {
  return String(status) === '1' ? '在售中' : '暂不可购买'
}

function sellerSince(value?: string) {
  if (!value) return '已完成身份注册'
  return `${new Date(value).getFullYear()} 年加入`
}

function categoryText(categoryId?: number) {
  const categories: Record<number, string> = {
    1: '手机数码',
    2: '电脑办公',
    3: '家电家具',
    4: '图书乐器',
    5: '服饰鞋包',
  }
  return categoryId ? categories[categoryId] || '其他闲置' : '其他闲置'
}

onMounted(load)
</script>

<template>
  <div class="product-detail-page">
    <button class="back-link" type="button" @click="router.back()">
      <ArrowLeft :size="18" aria-hidden="true" /> 返回
    </button>

    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="loading" class="detail-loading">正在加载商品...</div>

    <template v-else-if="product">
      <section class="product-detail-main">
        <div class="gallery">
          <div class="gallery-stage">
            <img v-if="currentImage" :src="currentImage" :alt="product.title" />
            <div v-else class="gallery-empty">
              <ShoppingBag :size="42" aria-hidden="true" />
              <span>卖家还没有上传图片</span>
            </div>
          </div>
          <div v-if="images.length > 1" class="gallery-thumbs">
            <button
              v-for="(image, index) in images"
              :key="image"
              :class="{ active: activeImage === index }"
              type="button"
              @click="activeImage = index"
            >
              <img :src="image" :alt="`${product.title} 图片 ${index + 1}`" />
            </button>
          </div>
        </div>

        <div class="purchase-panel">
          <div class="status-row">
            <span class="sale-status">{{ statusText(product.status) }}</span>
            <span>{{ conditionText(product.conditionLevel) }}</span>
          </div>
          <h1>{{ product.title }}</h1>
          <div class="detail-price">
            <span>¥</span><strong>{{ price(product.price) }}</strong>
            <del v-if="originalPriceVisible">¥{{ price(product.originalPrice) }}</del>
          </div>
          <div class="detail-meta">
            <span><MapPin :size="17" aria-hidden="true" />{{ product.locationText || '交易地点可协商' }}</span>
            <span><Eye :size="17" aria-hidden="true" />{{ product.viewCount ?? 0 }} 人看过</span>
            <span><Heart :size="17" aria-hidden="true" />{{ product.favoriteCount ?? 0 }} 人收藏</span>
          </div>

          <div class="trade-promises">
            <div><BadgeCheck :size="19" aria-hidden="true" /><span><strong>如实描述</strong>交易前确认瑕疵与配件</span></div>
            <div><ShieldCheck :size="19" aria-hidden="true" /><span><strong>订单留痕</strong>支付、发货与收货状态可查</span></div>
          </div>

          <section class="seller-summary" aria-label="卖家信息">
            <div class="seller-avatar">
              <img v-if="product.sellerAvatar" :src="product.sellerAvatar" :alt="product.sellerNickname || '卖家头像'" />
              <UserRound v-else :size="24" aria-hidden="true" />
            </div>
            <div>
              <strong>{{ product.sellerNickname || '闲置卖家' }}</strong>
              <span><CalendarDays :size="14" aria-hidden="true" />{{ sellerSince(product.sellerCreatedTime) }}</span>
            </div>
            <button class="seller-help" type="button" @click="router.push('/ai/rag-chat')">
              <MessageCircleQuestion :size="17" aria-hidden="true" />
              交易咨询
            </button>
          </section>

          <label class="remark-field">
            给卖家留言
            <textarea v-model="remark" rows="3" maxlength="255" placeholder="例如：方便同城面交吗？配件是否齐全？" />
          </label>

          <p v-if="notice" class="action-notice">{{ notice }}</p>
          <div class="purchase-actions">
            <button class="favorite-action" type="button" :disabled="actionLoading" @click="favorite">
              <Heart :size="19" aria-hidden="true" /> 收藏
            </button>
            <button
              class="primary-btn buy-action"
              type="button"
              :disabled="actionLoading || String(product.status) !== '1'"
              @click="buy"
            >
              <PackageCheck :size="19" aria-hidden="true" />
              {{ actionLoading ? '处理中...' : '立即购买' }}
            </button>
          </div>
          <p class="trade-tip">下单前请确认商品成色、配件和交易方式，不要脱离平台沟通付款。</p>
          <p v-if="auth.isInternal" class="internal-hint">商品 ID {{ product.id }} · 卖家 ID {{ product.sellerId }}</p>
        </div>
      </section>

      <section class="product-description">
        <div class="description-heading">
          <p class="eyebrow">商品详情</p>
          <h2>卖家描述</h2>
        </div>
        <p>{{ product.description || '卖家暂未补充详细描述，建议下单前进一步沟通。' }}</p>
        <dl>
          <div><dt>成色</dt><dd>{{ conditionText(product.conditionLevel) }}</dd></div>
          <div><dt>发布时间</dt><dd>{{ product.publishTime || '—' }}</dd></div>
          <div><dt>交易地点</dt><dd>{{ product.locationText || '可协商' }}</dd></div>
          <div><dt>分类</dt><dd>{{ categoryText(product.categoryId) }}</dd></div>
        </dl>
      </section>
    </template>
  </div>
</template>

<style scoped>
.product-detail-page {
  display: grid;
  gap: 22px;
}

.back-link {
  align-items: center;
  background: transparent;
  border: 0;
  display: inline-flex;
  gap: 6px;
  justify-self: start;
  padding: 4px 0;
}

.back-link:hover {
  background: transparent;
  color: var(--accent);
}

.detail-loading {
  color: var(--ink-500);
  padding: 80px 0;
  text-align: center;
}

.product-detail-main {
  display: grid;
  gap: clamp(24px, 4vw, 54px);
  grid-template-columns: minmax(0, 1.12fr) minmax(340px, 0.88fr);
}

.gallery {
  display: grid;
  gap: 10px;
}

.gallery-stage {
  aspect-ratio: 1 / 0.76;
  background: #edf1ee;
  overflow: hidden;
}

.gallery-stage img {
  height: 100%;
  object-fit: contain;
  width: 100%;
}

.gallery-empty {
  align-content: center;
  color: var(--ink-500);
  display: grid;
  gap: 10px;
  height: 100%;
  justify-items: center;
}

.gallery-thumbs {
  display: flex;
  gap: 9px;
  overflow-x: auto;
}

.gallery-thumbs button {
  aspect-ratio: 1;
  border: 2px solid transparent;
  padding: 0;
  width: 72px;
}

.gallery-thumbs button.active {
  border-color: var(--accent);
}

.gallery-thumbs img {
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.purchase-panel {
  align-self: start;
  display: grid;
  gap: 18px;
  position: sticky;
  top: 92px;
}

.status-row {
  color: var(--ink-500);
  display: flex;
  font-size: 13px;
  gap: 10px;
}

.sale-status {
  color: var(--accent);
  font-weight: 800;
}

.purchase-panel h1 {
  font-size: 34px;
  line-height: 1.35;
  margin: 0;
}

.detail-price {
  align-items: baseline;
  color: var(--accent-warm);
  display: flex;
  gap: 4px;
}

.detail-price strong {
  font-size: 42px;
}

.detail-price del {
  color: var(--ink-500);
  font-size: 14px;
  margin-left: 8px;
}

.detail-meta {
  color: var(--ink-500);
  display: flex;
  flex-wrap: wrap;
  font-size: 13px;
  gap: 14px;
}

.detail-meta span {
  align-items: center;
  display: inline-flex;
  gap: 5px;
}

.trade-promises {
  border-block: 1px solid var(--line);
  display: grid;
  gap: 12px;
  padding: 16px 0;
}

.trade-promises > div {
  align-items: center;
  display: flex;
  gap: 10px;
}

.trade-promises svg {
  color: var(--accent);
}

.trade-promises span {
  color: var(--ink-500);
  display: grid;
  font-size: 12px;
  gap: 2px;
}

.trade-promises strong {
  color: var(--ink-900);
  font-size: 14px;
}

.seller-summary {
  align-items: center;
  border-block: 1px solid var(--line);
  display: grid;
  gap: 12px;
  grid-template-columns: auto 1fr auto;
  padding: 14px 0;
}

.seller-avatar {
  align-items: center;
  background: var(--accent-soft);
  border-radius: 50%;
  color: var(--accent);
  display: flex;
  height: 46px;
  justify-content: center;
  overflow: hidden;
  width: 46px;
}

.seller-avatar img {
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.seller-summary > div:nth-child(2) {
  display: grid;
  gap: 4px;
}

.seller-summary span {
  align-items: center;
  color: var(--ink-500);
  display: inline-flex;
  font-size: 12px;
  gap: 5px;
}

.seller-help {
  align-items: center;
  display: inline-flex;
  gap: 6px;
  padding: 8px 10px;
}

.remark-field {
  margin: 0;
}

.action-notice {
  background: var(--accent-soft);
  color: var(--accent);
  margin: 0;
  padding: 10px 12px;
}

.purchase-actions {
  display: grid;
  gap: 10px;
  grid-template-columns: 120px 1fr;
}

.purchase-actions button {
  align-items: center;
  display: inline-flex;
  font-weight: 750;
  gap: 7px;
  justify-content: center;
  min-height: 48px;
}

.favorite-action {
  background: #fff;
}

.buy-action {
  background: var(--accent-warm);
  border-color: var(--accent-warm);
}

.trade-tip {
  background: #fff7ed;
  color: #8a4b12;
  font-size: 12px;
  line-height: 1.6;
  margin: -4px 0 0;
  padding: 9px 11px;
}

.internal-hint {
  color: var(--ink-500);
  font-size: 12px;
  margin: 0;
}

.product-description {
  border-top: 1px solid var(--line);
  display: grid;
  gap: 18px;
  grid-template-columns: minmax(180px, 0.32fr) minmax(0, 0.68fr);
  padding-top: 28px;
}

.description-heading h2 {
  margin: 4px 0 0;
}

.product-description > p {
  line-height: 1.85;
  margin: 0;
  white-space: pre-wrap;
}

.product-description dl {
  display: grid;
  gap: 0;
  grid-column: 2;
  margin: 0;
}

.product-description dl > div {
  border-top: 1px solid var(--line);
  display: grid;
  grid-template-columns: 110px 1fr;
  padding: 12px 0;
}

.product-description dt {
  color: var(--ink-500);
}

.product-description dd {
  margin: 0;
}

@media (max-width: 860px) {
  .product-detail-main,
  .product-description {
    grid-template-columns: 1fr;
  }

  .purchase-panel {
    position: static;
  }

  .product-description dl {
    grid-column: auto;
  }
}

@media (max-width: 520px) {
  .purchase-actions {
    grid-template-columns: 1fr;
  }

  .detail-price strong {
    font-size: 34px;
  }

  .purchase-panel h1 {
    font-size: 27px;
  }

  .seller-summary {
    grid-template-columns: auto 1fr;
  }

  .seller-help {
    grid-column: 1 / -1;
    justify-content: center;
    width: 100%;
  }
}
</style>
