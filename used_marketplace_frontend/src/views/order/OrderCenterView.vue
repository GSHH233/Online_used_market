<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Banknote,
  Box,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  PackageCheck,
  PackageOpen,
  Truck,
  XCircle,
} from 'lucide-vue-next'
import { orderApi } from '../../api/modules/order'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const error = ref('')
const notice = ref('')
const result = ref<any>(null)
const detail = ref<any>(null)
const query = reactive({ role: 'BUYER', status: '', pageNum: 1, pageSize: 10 })

const items = computed(() => result.value?.records || result.value?.data?.records || [])
const total = computed(() => Number(result.value?.total || result.value?.data?.total || 0))
const pages = computed(() => Number(result.value?.pages || result.value?.data?.pages || 0))
const orderDetail = computed(() => detail.value?.data || detail.value)

const statuses = [
  { value: '', label: '全部' },
  { value: '0', label: '待支付' },
  { value: '1', label: '待发货' },
  { value: '2', label: '待收货' },
  { value: '3', label: '已完成' },
  { value: '4', label: '已取消' },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    result.value = await orderApi.listOrders({ ...query, status: query.status || undefined })
  } catch (e: any) {
    error.value = e?.message || '获取订单失败'
  } finally {
    loading.value = false
  }
}

async function view(orderId: number | string) {
  loading.value = true
  try {
    detail.value = await orderApi.getOrderDetail(orderId)
  } catch (e: any) {
    error.value = e?.message || '获取订单详情失败'
  } finally {
    loading.value = false
  }
}

async function mutate(action: 'cancel' | 'pay' | 'deliver' | 'finish', orderId: number | string) {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const map = {
      cancel: orderApi.cancelOrder,
      pay: orderApi.payOrder,
      deliver: orderApi.deliverOrder,
      finish: orderApi.finishOrder,
    } as const
    await map[action](orderId)
    notice.value = {
      cancel: '订单已取消',
      pay: '支付状态已更新',
      deliver: '订单已标记为发货',
      finish: '已确认收货，交易完成',
    }[action]
    await load()
    if (orderDetail.value?.id === orderId) await view(orderId)
  } catch (e: any) {
    error.value = e?.message || '操作失败'
  } finally {
    loading.value = false
  }
}

function selectRole(role: string) {
  query.role = role
  query.status = ''
  query.pageNum = 1
  detail.value = null
  load()
}

function selectStatus(status: string) {
  query.status = status
  query.pageNum = 1
  detail.value = null
  load()
}

function prev() {
  if (query.pageNum <= 1) return
  query.pageNum -= 1
  load()
}

function next() {
  if (pages.value && query.pageNum >= pages.value) return
  query.pageNum += 1
  load()
}

function statusText(status?: number | string) {
  const map: Record<string, string> = {
    '0': '等待买家付款',
    '1': '买家已付款',
    '2': '卖家已发货',
    '3': '交易已完成',
    '4': '订单已取消',
  }
  return map[String(status ?? '')] || '状态未知'
}

function statusClass(status?: number | string) {
  return `order-status status-${String(status ?? 'unknown')}`
}

function statusIcon(status?: number | string) {
  return {
    '0': Clock3,
    '1': Banknote,
    '2': Truck,
    '3': CheckCircle2,
    '4': XCircle,
  }[String(status ?? '')] || Box
}

function canPay(item: any) {
  return query.role === 'BUYER' && String(item?.status) === '0'
}

function canCancel(item: any) {
  return query.role === 'BUYER' && String(item?.status) === '0'
}

function canDeliver(item: any) {
  return query.role === 'SELLER' && String(item?.status) === '1'
}

function canFinish(item: any) {
  return query.role === 'BUYER' && String(item?.status) === '2'
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function formatPrice(value?: number | string) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric.toLocaleString('zh-CN') : value || '—'
}

onMounted(load)
</script>

<template>
  <div class="order-page">
    <header class="order-heading">
      <div>
        <p class="eyebrow">交易进度</p>
        <h1>订单中心</h1>
        <p>管理买入和卖出的商品，重要状态变化会保留在订单记录中。</p>
      </div>
      <span>{{ total }} 笔订单</span>
    </header>

    <nav class="order-role-tabs" aria-label="订单视角">
      <button :class="{ active: query.role === 'BUYER' }" type="button" @click="selectRole('BUYER')">我买到的</button>
      <button :class="{ active: query.role === 'SELLER' }" type="button" @click="selectRole('SELLER')">我卖出的</button>
    </nav>

    <nav class="order-status-tabs" aria-label="订单状态">
      <button
        v-for="item in statuses"
        :key="item.value"
        :class="{ active: query.status === item.value }"
        type="button"
        @click="selectStatus(item.value)"
      >
        {{ item.label }}
      </button>
    </nav>

    <p v-if="notice" class="market-notice">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div class="order-content">
      <section class="order-list" aria-label="订单列表">
        <div v-if="loading && !items.length" class="order-loading">正在加载订单...</div>
        <article v-for="item in items" v-else :key="item.id" class="order-card">
          <header>
            <span>下单时间 {{ formatDate(item.createdTime) }}</span>
            <span>订单号 {{ item.orderNo }}</span>
            <strong :class="statusClass(item.status)">
              <component :is="statusIcon(item.status)" :size="16" aria-hidden="true" />
              {{ statusText(item.status) }}
            </strong>
          </header>
          <div class="order-product">
            <div class="order-cover">
              <img v-if="item.productMainImage" :src="item.productMainImage" :alt="item.productTitle" />
              <PackageOpen v-else :size="28" aria-hidden="true" />
            </div>
            <div class="order-product-copy">
              <h2>{{ item.productTitle || '未命名商品' }}</h2>
              <p>{{ item.remark ? `留言：${item.remark}` : '无订单留言' }}</p>
            </div>
            <div class="order-amount">
              <span>实付款</span>
              <strong>¥{{ formatPrice(item.orderAmount) }}</strong>
            </div>
            <div class="order-actions">
              <button class="secondary-btn" type="button" @click="view(item.id)">订单详情</button>
              <button v-if="canPay(item)" class="primary-btn" type="button" @click="mutate('pay', item.id)">立即支付</button>
              <button v-if="canCancel(item)" class="text-order-action" type="button" @click="mutate('cancel', item.id)">取消订单</button>
              <button v-if="canDeliver(item)" class="primary-btn" type="button" @click="mutate('deliver', item.id)">确认发货</button>
              <button v-if="canFinish(item)" class="primary-btn" type="button" @click="mutate('finish', item.id)">确认收货</button>
            </div>
          </div>
        </article>

        <section v-if="!loading && !items.length" class="order-empty">
          <PackageCheck :size="38" aria-hidden="true" />
          <h2>这里还没有订单</h2>
          <p>{{ query.role === 'BUYER' ? '去逛逛商品，遇到合适的再下单。' : '发布闲置后，买家的订单会出现在这里。' }}</p>
        </section>

        <nav v-if="items.length" class="market-pagination" aria-label="订单分页">
          <button class="icon-btn" type="button" :disabled="loading || query.pageNum <= 1" title="上一页" @click="prev">
            <ChevronLeft :size="19" aria-hidden="true" />
          </button>
          <span>第 {{ query.pageNum }} / {{ pages || 1 }} 页</span>
          <button class="icon-btn" type="button" :disabled="loading || (pages > 0 && query.pageNum >= pages)" title="下一页" @click="next">
            <ChevronRight :size="19" aria-hidden="true" />
          </button>
        </nav>
      </section>

      <aside class="order-detail" :class="{ empty: !orderDetail }">
        <template v-if="orderDetail">
          <div class="detail-title">
            <div>
              <p class="eyebrow">订单详情</p>
              <h2>{{ statusText(orderDetail.status) }}</h2>
            </div>
            <component :is="statusIcon(orderDetail.status)" :size="26" aria-hidden="true" />
          </div>
          <div class="detail-product">
            <img v-if="orderDetail.productMainImage" :src="orderDetail.productMainImage" :alt="orderDetail.productTitle" />
            <div>
              <strong>{{ orderDetail.productTitle || '未命名商品' }}</strong>
              <span>¥{{ formatPrice(orderDetail.orderAmount ?? orderDetail.productPrice) }}</span>
            </div>
          </div>
          <dl>
            <div><dt>订单号</dt><dd>{{ orderDetail.orderNo || '—' }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ formatDate(orderDetail.createdTime) }}</dd></div>
            <div><dt>支付时间</dt><dd>{{ formatDate(orderDetail.payTime) }}</dd></div>
            <div><dt>发货时间</dt><dd>{{ formatDate(orderDetail.deliverTime) }}</dd></div>
            <div><dt>完成时间</dt><dd>{{ formatDate(orderDetail.finishTime) }}</dd></div>
            <div><dt>买家留言</dt><dd>{{ orderDetail.remark || '无' }}</dd></div>
          </dl>
        </template>
        <template v-else>
          <Box :size="34" aria-hidden="true" />
          <h2>选择一笔订单</h2>
          <p>订单时间、交易状态和商品快照会显示在这里。</p>
        </template>
      </aside>
    </div>

    <details v-if="auth.isInternal" class="panel">
      <summary>原始响应</summary>
      <pre>{{ result || '暂无结果' }}</pre>
    </details>
  </div>
</template>

<style scoped>
.order-page {
  display: grid;
  gap: 16px;
}

.order-heading {
  align-items: end;
  display: flex;
  justify-content: space-between;
  padding: 18px 0 8px;
}

details summary {
  cursor: pointer;
  font-weight: 800;
}

.order-heading h1 {
  font-size: 38px;
  margin: 5px 0 8px;
}

.order-heading p:last-child,
.order-heading > span {
  color: var(--ink-500);
  margin: 0;
}

.order-role-tabs {
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 24px;
}

.order-role-tabs button {
  background: transparent;
  border: 0;
  border-bottom: 3px solid transparent;
  border-radius: 0;
  font-size: 16px;
  font-weight: 750;
  padding: 11px 2px;
}

.order-role-tabs button.active {
  border-bottom-color: var(--accent);
  color: var(--accent);
}

.order-status-tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
}

.order-status-tabs button {
  background: #fff;
  border-color: var(--line);
  flex: 0 0 auto;
  padding: 8px 13px;
}

.order-status-tabs button.active {
  background: var(--ink-900);
  border-color: var(--ink-900);
  color: #fff;
}

.order-content {
  align-items: start;
  display: grid;
  gap: 18px;
  grid-template-columns: minmax(0, 1fr) 340px;
}

.order-list {
  display: grid;
  gap: 12px;
}

.order-card {
  background: #fff;
  border: 1px solid var(--line);
}

.order-card > header {
  align-items: center;
  background: #f7f9f8;
  border-bottom: 1px solid var(--line);
  color: var(--ink-500);
  display: grid;
  font-size: 12px;
  gap: 16px;
  grid-template-columns: auto 1fr auto;
  padding: 10px 13px;
}

.order-status {
  align-items: center;
  display: inline-flex;
  gap: 5px;
}

.status-0 {
  color: #9b5d12;
}

.status-1,
.status-2 {
  color: var(--running);
}

.status-3 {
  color: var(--success);
}

.status-4 {
  color: var(--ink-500);
}

.order-product {
  align-items: center;
  display: grid;
  gap: 14px;
  grid-template-columns: 92px minmax(0, 1fr) auto auto;
  padding: 14px;
}

.order-cover {
  align-items: center;
  aspect-ratio: 1;
  background: #edf1ee;
  color: var(--ink-500);
  display: flex;
  justify-content: center;
  overflow: hidden;
}

.order-cover img {
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.order-product-copy {
  min-width: 0;
}

.order-product-copy h2 {
  font-size: 15px;
  margin: 0 0 7px;
}

.order-product-copy p {
  color: var(--ink-500);
  font-size: 12px;
  margin: 0;
}

.order-amount {
  display: grid;
  gap: 3px;
  text-align: right;
}

.order-amount span {
  color: var(--ink-500);
  font-size: 11px;
}

.order-amount strong {
  color: var(--accent-warm);
  font-size: 18px;
}

.order-actions {
  display: grid;
  gap: 7px;
  min-width: 96px;
}

.order-actions button {
  padding: 7px 10px;
}

.text-order-action {
  background: transparent;
  border: 0;
  color: var(--ink-500);
}

.order-detail {
  background: #fff;
  border: 1px solid var(--line);
  display: grid;
  gap: 18px;
  padding: 18px;
  position: sticky;
  top: 88px;
}

.order-detail.empty {
  align-content: center;
  color: var(--ink-500);
  justify-items: center;
  min-height: 260px;
  text-align: center;
}

.order-detail.empty h2 {
  color: var(--ink-900);
  margin: 0;
}

.order-detail.empty p {
  margin: 0;
}

.detail-title {
  align-items: center;
  color: var(--accent);
  display: flex;
  justify-content: space-between;
}

.detail-title h2 {
  color: var(--ink-900);
  margin: 4px 0 0;
}

.detail-product {
  align-items: center;
  display: grid;
  gap: 11px;
  grid-template-columns: 64px 1fr;
}

.detail-product img {
  aspect-ratio: 1;
  object-fit: cover;
  width: 64px;
}

.detail-product div {
  display: grid;
  gap: 5px;
}

.detail-product span {
  color: var(--accent-warm);
  font-weight: 800;
}

.order-detail dl {
  display: grid;
  gap: 10px;
  margin: 0;
}

.order-detail dl div {
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 5px;
  padding-bottom: 9px;
}

.order-detail dt {
  color: var(--ink-500);
  font-size: 11px;
}

.order-detail dd {
  font-size: 13px;
  margin: 0;
  overflow-wrap: anywhere;
}

.order-empty,
.order-loading {
  align-content: center;
  background: #fff;
  border: 1px solid var(--line);
  color: var(--ink-500);
  display: grid;
  justify-items: center;
  min-height: 300px;
  padding: 30px;
  text-align: center;
}

.order-empty h2 {
  color: var(--ink-900);
  margin: 14px 0 5px;
}

.order-empty p {
  margin: 0;
}

@media (max-width: 1050px) {
  .order-content {
    grid-template-columns: 1fr;
  }

  .order-detail {
    position: static;
  }
}

@media (max-width: 760px) {
  .order-heading h1 {
    font-size: 30px;
  }

  .order-product {
    align-items: start;
    grid-template-columns: 76px minmax(0, 1fr);
  }

  .order-amount {
    grid-column: 2;
    text-align: left;
  }

  .order-actions {
    display: flex;
    flex-wrap: wrap;
    grid-column: 1 / -1;
  }

  .order-card > header {
    gap: 4px;
    grid-template-columns: 1fr;
  }
}
</style>
