<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Check, FileText, ImagePlus, Sparkles } from 'lucide-vue-next'
import {
  publishAssistantApi,
  type PublishAssistResult,
} from '../../api/modules/publishAssistant'
import { productApi } from '../../api/modules/product'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const PRODUCT_DRAFT_KEY = 'ai_publish_product_draft'
const PRODUCT_FORM_DRAFT_KEY = 'product_publish_form_draft'

const form = reactive({
  title: '',
  description: '',
  price: '',
  originalPrice: '',
  conditionLevel: 8,
  categoryId: '',
  locationText: '',
  imageUrls: [] as string[],
})

const uploading = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')
const draftNotice = ref('')
const assistLoading = ref(false)
const assistError = ref('')
const assistResult = ref<PublishAssistResult | null>(null)
const adoptedFields = ref<string[]>([])
const manualImageUrl = ref('')
const allowManualImageUrl = import.meta.env.VITE_ENABLE_AI_DEBUG === 'true'
const categoryOptions = [
  { value: '1', label: '手机数码' },
  { value: '2', label: '电脑办公' },
  { value: '3', label: '家电家具' },
  { value: '4', label: '图书乐器' },
  { value: '5', label: '服饰鞋包' },
]

const coverPreview = computed(() => form.imageUrls[0] || '')
const assistOptimize = computed(() => assistResult.value?.optimizeResult || {})
const hasFormContent = computed(() => Boolean(
  form.title.trim()
  || form.description.trim()
  || form.price
  || form.originalPrice
  || form.categoryId
  || form.locationText.trim()
  || form.imageUrls.length
  || form.conditionLevel !== 8,
))
const assistWarnings = computed(() => {
  const set = new Set<string>()
  for (const item of assistResult.value?.riskWarnings || []) if (item) set.add(item)
  for (const item of assistOptimize.value.riskWarnings || []) if (item) set.add(item)
  return Array.from(set)
})
const assistDecision = computed(() => {
  if (!assistResult.value) return ''
  return assistResult.value.canPublish ? '建议发布' : '建议修改后再发布'
})
const conditionHint = computed(() => {
  if (form.conditionLevel >= 9) return '接近全新，建议补充购买凭证、保修状态和未拆封说明。'
  if (form.conditionLevel >= 7) return '正常使用，建议说明划痕、配件、维修记录和电池情况。'
  return '成色一般，建议把瑕疵写清楚，减少售后争议。'
})

function resetMessages() {
  error.value = ''
  success.value = ''
  assistError.value = ''
}

function conditionTextFromLevel(level: number) {
  if (level >= 9) return '接近全新'
  if (level >= 7) return '正常使用'
  return '有明显使用痕迹'
}

function addImageUrl(url: string) {
  const next = url.trim()
  if (!next) return
  if (form.imageUrls.includes(next)) {
    manualImageUrl.value = ''
    return
  }
  form.imageUrls.push(next)
  manualImageUrl.value = ''
}

function removeImageUrl(index: number) {
  form.imageUrls.splice(index, 1)
}

function addManualImageUrl() {
  addImageUrl(manualImageUrl.value)
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  resetMessages()
  try {
    const data = await productApi.uploadFile(file)
    const url = data?.url || data?.fileUrl || data?.path
    if (!url) throw new Error('上传成功，但后端没有返回图片地址')
    addImageUrl(url)
  } catch (e: any) {
    error.value = e?.message || '图片上传失败，请稍后重试或更换图片。'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function submit() {
  resetMessages()
  if (!auth.isLoggedIn) {
    error.value = '请先登录后再发布商品。'
    router.push('/login')
    return
  }
  if (!form.title.trim()) return (error.value = '请输入商品标题')
  if (!form.description.trim()) return (error.value = '请补充商品描述')
  if (!form.price || Number(form.price) <= 0) return (error.value = '请输入大于 0 的商品价格')
  if (!form.conditionLevel || form.conditionLevel < 1 || form.conditionLevel > 10) return (error.value = '成色需要在 1-10 之间')
  if (!form.imageUrls.length) return (error.value = '请至少上传一张图片，或粘贴一条图片 URL')

  saving.value = true
  try {
    const created = await productApi.createProduct({
      title: form.title.trim(),
      description: form.description.trim(),
      price: Number(form.price),
      originalPrice: form.originalPrice ? Number(form.originalPrice) : undefined,
      conditionLevel: form.conditionLevel,
      categoryId: form.categoryId ? Number(form.categoryId) : undefined,
      locationText: form.locationText.trim() || undefined,
      imageUrls: form.imageUrls,
      assistId: assistResult.value?.assistId,
      assistAdoptionType: assistResult.value
        ? adoptedFields.value.length >= 2
          ? 'APPLIED'
          : adoptedFields.value.length
            ? 'PARTIAL'
            : 'REJECTED'
        : undefined,
      assistAdoptedFields: adoptedFields.value,
    })
    const productId = (created as any)?.productId || (created as any)?.id
    success.value = '商品发布成功，正在跳转到详情页。'
    clearFormDraft()
    resetForm()
    setTimeout(() => {
      if (productId) router.push(`/products/${productId}`)
      else router.push('/products')
    }, 500)
  } catch (e: any) {
    error.value = e?.message || '发布失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

async function runAssistFromForm() {
  resetMessages()
  if (!form.title.trim()) return (assistError.value = '请先填写商品标题')
  if (!form.description.trim()) return (assistError.value = '请先填写商品描述')
  assistLoading.value = true
  try {
    assistResult.value = await publishAssistantApi.analyze({
      title: form.title.trim(),
      description: form.description.trim(),
      brand: '',
      conditionText: conditionTextFromLevel(form.conditionLevel),
      categoryName: form.categoryId ? `分类 ${form.categoryId}` : '',
      expectedPrice: Number(form.price || 0),
    })
    adoptedFields.value = []
  } catch (e: any) {
    assistError.value = e?.message || 'AI 检查失败，可以先手动发布，稍后再补充优化。'
  } finally {
    assistLoading.value = false
  }
}

function applyAssistField(field: 'title' | 'description') {
  const value = field === 'title'
    ? assistOptimize.value.optimizedTitle
    : assistOptimize.value.optimizedDescription
  if (!value) return
  form[field] = value
  if (!adoptedFields.value.includes(field)) adoptedFields.value.push(field)
  success.value = `已采用${field === 'title' ? '标题' : '描述'}建议，请确认内容与实物一致。`
  if (assistResult.value?.assistId) {
    publishAssistantApi.feedback(assistResult.value.assistId, {
      adoptionType: 'PARTIAL',
      adoptedFields: adoptedFields.value,
      reason: 'user_applied_ai_field_before_publish',
    }).catch(() => {
      assistError.value = '已套用文案，但采纳反馈暂未写入。'
    })
  }
}

function resetForm() {
  form.title = ''
  form.description = ''
  form.price = ''
  form.originalPrice = ''
  form.conditionLevel = 8
  form.categoryId = ''
  form.locationText = ''
  form.imageUrls = []
  manualImageUrl.value = ''
  assistResult.value = null
  adoptedFields.value = []
  draftNotice.value = ''
}

function saveFormDraft() {
  if (!hasFormContent.value) {
    localStorage.removeItem(PRODUCT_FORM_DRAFT_KEY)
    return
  }
  localStorage.setItem(PRODUCT_FORM_DRAFT_KEY, JSON.stringify({
    title: form.title,
    description: form.description,
    price: form.price,
    originalPrice: form.originalPrice,
    conditionLevel: form.conditionLevel,
    categoryId: form.categoryId,
    locationText: form.locationText,
    imageUrls: form.imageUrls,
  }))
}

function clearFormDraft() {
  localStorage.removeItem(PRODUCT_FORM_DRAFT_KEY)
}

function restoreSavedDraft() {
  const raw = localStorage.getItem(PRODUCT_FORM_DRAFT_KEY)
  if (!raw) return
  try {
    const draft = JSON.parse(raw)
    form.title = draft.title || ''
    form.description = draft.description || ''
    form.price = draft.price || ''
    form.originalPrice = draft.originalPrice || ''
    form.conditionLevel = Number(draft.conditionLevel || 8)
    form.categoryId = draft.categoryId || ''
    form.locationText = draft.locationText || ''
    form.imageUrls = Array.isArray(draft.imageUrls) ? draft.imageUrls : []
    draftNotice.value = '已恢复上次未发布的草稿'
  } catch {
    clearFormDraft()
  }
}

function loadAiDraft() {
  const raw = localStorage.getItem(PRODUCT_DRAFT_KEY)
  if (!raw) return
  try {
    const draft = JSON.parse(raw)
    form.title = draft.title || form.title
    form.description = draft.description || form.description
    form.price = draft.price || form.price
    form.conditionLevel = Number(draft.conditionLevel || form.conditionLevel)
    assistResult.value = {
      assistId: draft.assistId,
      traceId: draft.traceId,
      canPublish: draft.aiDecision === '建议发布',
      riskScore: draft.riskScore,
      priceSuggestion: draft.priceSuggestion,
      riskWarnings: Array.isArray(draft.riskWarnings) ? draft.riskWarnings : [],
      optimizeResult: {
        optimizedTitle: draft.title,
        optimizedDescription: draft.description,
      },
    } as PublishAssistResult
    draftNotice.value = '已从发布助手带入优化文案'
    localStorage.removeItem(PRODUCT_DRAFT_KEY)
  } catch {
    localStorage.removeItem(PRODUCT_DRAFT_KEY)
  }
}

function clearCurrentDraft() {
  resetForm()
  clearFormDraft()
  success.value = '已清空本地草稿'
  error.value = ''
}

onMounted(() => {
  restoreSavedDraft()
  loadAiDraft()
})

watch(form, saveFormDraft, { deep: true })
</script>

<template>
  <div class="page-shell publish-page">
    <section class="hero-card ink-hero-card">
      <div>
        <p class="eyebrow">发布商品</p>
        <h2>把闲置写清楚，再放心上架</h2>
        <p class="muted">标题、成色、价格和图片越完整，买家越容易判断，也能减少交易争议。</p>
      </div>
      <div class="chip-row">
        <button class="secondary-btn" type="button" :disabled="!hasFormContent" @click="clearCurrentDraft">清空草稿</button>
      </div>
    </section>

    <section class="panel ai-publish-panel">
      <div class="ai-panel-header">
        <div>
          <p class="eyebrow">AI 文案检查</p>
          <h3>检查风险并生成可套用文案</h3>
          <p class="muted">AI 只辅助检查，最终发布前仍需要你确认商品真实情况。</p>
        </div>
        <button class="primary-btn" type="button" :disabled="assistLoading" @click="runAssistFromForm">
          <Sparkles :size="17" aria-hidden="true" />
          {{ assistLoading ? '检查中...' : '检查当前文案' }}
        </button>
      </div>

      <p v-if="draftNotice" class="success-msg">{{ draftNotice }}</p>
      <p v-if="assistError" class="error">{{ assistError }}</p>
      <p v-if="assistResult?.fallbackUsed" class="fallback-note">
        当前由规则与知识证据完成检查，模型生成暂不可用。{{ assistResult.fallbackReason || '' }}
      </p>

      <div v-if="assistResult" class="ai-result-grid">
        <div class="ai-summary">
          <span>发布结论</span>
          <strong>{{ assistDecision }}</strong>
        </div>
        <div class="ai-summary">
          <span>风险评分</span>
          <strong>{{ assistResult.riskScore ?? '暂无' }}</strong>
        </div>
        <div class="ai-copy-card">
          <span>优化标题</span>
          <p>{{ assistOptimize.optimizedTitle || '暂无标题建议' }}</p>
          <button
            v-if="assistOptimize.optimizedTitle"
            class="secondary-btn"
            type="button"
            @click="applyAssistField('title')"
          >
            <Check :size="15" aria-hidden="true" />
            {{ adoptedFields.includes('title') ? '已采用' : '采用标题' }}
          </button>
        </div>
        <div class="ai-copy-card">
          <span>优化描述</span>
          <p>{{ assistOptimize.optimizedDescription || '暂无描述建议' }}</p>
          <button
            v-if="assistOptimize.optimizedDescription"
            class="secondary-btn"
            type="button"
            @click="applyAssistField('description')"
          >
            <Check :size="15" aria-hidden="true" />
            {{ adoptedFields.includes('description') ? '已采用' : '采用描述' }}
          </button>
        </div>
      </div>

      <div v-if="assistResult" class="ai-actions">
        <p class="muted">{{ assistResult.priceSuggestion || '价格建议仅作参考，不会自动覆盖你填写的价格。' }}</p>
      </div>

      <ul v-if="assistWarnings.length" class="warning-list">
        <li v-for="item in assistWarnings" :key="item">
          <AlertTriangle :size="15" aria-hidden="true" />
          {{ item }}
        </li>
      </ul>

      <details v-if="assistResult?.riskEvidence?.length || assistResult?.sources?.length" class="evidence-panel">
        <summary><FileText :size="16" aria-hidden="true" />查看判断依据</summary>
        <div class="evidence-list">
          <article
            v-for="(item, index) in (assistResult.riskEvidence?.length ? assistResult.riskEvidence : assistResult.sources)"
            :key="`${item.docId || 0}-${item.chunkId || index}`"
          >
            <strong>{{ item.title || `依据 ${index + 1}` }}</strong>
            <p>{{ item.content || item.source || '知识库规则命中' }}</p>
          </article>
        </div>
      </details>
    </section>

    <div class="grid two-col">
      <section class="panel">
        <h3>商品信息</h3>
        <label>商品标题<input v-model="form.title" placeholder="例如：自用 iPhone 13 128G，电池健康 86%" /></label>
        <label>商品描述<textarea v-model="form.description" rows="7" placeholder="说明来源、成色、配件、瑕疵、维修记录和交易方式" /></label>
        <div class="grid two-col">
          <label>价格<input v-model="form.price" type="number" min="0.01" step="0.01" placeholder="0.00" /></label>
          <label>原价<input v-model="form.originalPrice" type="number" min="0" step="0.01" placeholder="可选" /></label>
        </div>
        <div class="grid two-col">
          <label>成色 1-10<input v-model.number="form.conditionLevel" type="number" min="1" max="10" /></label>
          <label>
            商品分类
            <select v-model="form.categoryId">
              <option value="">请选择分类</option>
              <option v-for="item in categoryOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
        </div>
        <p class="hint">{{ conditionHint }}</p>
        <label>交易地点<input v-model="form.locationText" placeholder="例如：深圳南山，可地铁站面交" /></label>
      </section>

      <section class="panel">
        <h3>图片</h3>
        <label class="upload-box">
          <ImagePlus :size="24" aria-hidden="true" />
          <span>{{ uploading ? '正在上传...' : '选择图片上传' }}</span>
          <input type="file" accept="image/*" @change="onFileChange" />
        </label>
        <p class="muted">支持 JPG、PNG 等常见图片格式，请至少上传一张清晰的商品实拍图。</p>

        <div v-if="coverPreview" class="product-cover large">
          <img :src="coverPreview" alt="封面预览" />
        </div>

        <label v-if="allowManualImageUrl">调试图片地址</label>
        <div v-if="allowManualImageUrl" class="inline-actions">
          <input v-model="manualImageUrl" placeholder="输入图片 URL 后回车" @keyup.enter="addManualImageUrl" />
          <button class="secondary-btn" type="button" @click="addManualImageUrl">添加</button>
        </div>

        <div v-if="form.imageUrls.length" class="timeline compact image-list">
          <div v-for="(url, index) in form.imageUrls" :key="url + index" class="timeline-item">
            <strong>图片 {{ index + 1 }}</strong>
            <p class="muted">{{ url }}</p>
            <button class="secondary-btn" type="button" @click="removeImageUrl(index)">移除</button>
          </div>
        </div>
      </section>
    </div>

    <section class="panel publish-actions">
      <div>
        <h3>确认发布</h3>
        <p class="muted">提交后商品会进入商城列表，买家可以查看详情并下单。</p>
      </div>
      <button class="primary-btn" type="button" :disabled="saving" @click="submit">
        {{ saving ? '发布中...' : '发布商品' }}
      </button>
      <p v-if="success" class="success-msg">{{ success }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </section>
  </div>
</template>

<style scoped>
.publish-page {
  gap: 20px;
}

.ai-publish-panel,
.publish-actions {
  display: grid;
  gap: 14px;
}

.ai-panel-header,
.publish-actions {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.ai-panel-header {
  gap: 16px;
}

.ai-panel-header h3,
.ai-panel-header p,
.publish-actions h3,
.publish-actions p {
  margin: 0;
}

.ai-panel-header .primary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  white-space: nowrap;
}

.ai-result-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.ai-summary,
.ai-copy-card {
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 13px;
}

.ai-summary {
  display: grid;
  gap: 4px;
}

.ai-summary span,
.ai-copy-card span {
  color: var(--ink-500);
  font-size: 12px;
}

.ai-summary strong {
  color: var(--ink-900);
  font-size: 20px;
}

.ai-copy-card p {
  line-height: 1.7;
  margin: 6px 0 0;
  white-space: pre-wrap;
}

.ai-copy-card .secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 6px;
  margin-top: 10px;
}

.ai-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.fallback-note {
  background: rgba(157, 116, 32, 0.08);
  border-left: 3px solid #9d7420;
  color: var(--ink-700);
  margin: 0;
  padding: 10px 12px;
}

.evidence-panel {
  border-top: 1px solid var(--line);
  padding-top: 12px;
}

.evidence-panel summary {
  align-items: center;
  cursor: pointer;
  display: inline-flex;
  font-weight: 750;
  gap: 7px;
}

.evidence-list {
  display: grid;
  gap: 9px;
  margin-top: 10px;
}

.evidence-list article {
  border-left: 2px solid var(--line-strong);
  padding-left: 11px;
}

.evidence-list p {
  color: var(--ink-700);
  line-height: 1.65;
  margin: 4px 0 0;
}

.ai-actions p {
  margin: 0;
}

.warning-list {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 0;
  padding: 0;
}

.warning-list li {
  align-items: flex-start;
  background: rgba(180, 35, 24, 0.06);
  border: 1px solid rgba(180, 35, 24, 0.16);
  border-radius: 8px;
  color: var(--failed);
  display: flex;
  gap: 8px;
  padding: 10px 12px;
}

.upload-box {
  align-items: center;
  background: rgba(255, 255, 255, 0.64);
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  gap: 10px;
  justify-content: center;
  margin-bottom: 10px;
  min-height: 96px;
  position: relative;
}

.upload-box input {
  inset: 0;
  opacity: 0;
  position: absolute;
}

.image-list {
  margin-top: 12px;
}

.image-list .timeline-item p {
  margin: 6px 0 10px;
}

.publish-actions .primary-btn {
  min-width: 140px;
}

@media (max-width: 760px) {
  .ai-panel-header,
  .publish-actions {
    align-items: stretch;
    display: grid;
  }

  .ai-result-grid {
    grid-template-columns: 1fr;
  }
}
</style>
