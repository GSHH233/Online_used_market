<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const mode = ref<'password' | 'sms'>('password')
const loading = ref(false)
const smsSending = ref(false)
const error = ref('')
const smsNotice = ref('')
const passwordVisible = ref(false)
const countdown = ref(0)
let timer: number | undefined

const passwordForm = reactive({
  account: '',
  password: '',
  loginType: 'USERNAME_PASSWORD',
})

const smsForm = reactive({
  phone: '',
  code: '',
})

const title = computed(() => (mode.value === 'password' ? '账号密码登录' : '短信验证码登录'))
const subtitle = computed(() =>
  mode.value === 'password'
    ? '登录后可以发布商品、查看订单，并使用 AI 检查发布风险。'
    : '输入手机号后发送验证码，再使用短信验证码完成登录。',
)

const sendButtonText = computed(() => {
  if (smsSending.value) return '发送中...'
  if (countdown.value > 0) return `${countdown.value}s 后重发`
  return '发送验证码'
})

const loginRedirect = computed(() => {
  const target = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  if (typeof target !== 'string') return '/market'
  if (!target.startsWith('/') || target.startsWith('//') || target.startsWith('/login')) return '/market'
  return target
})

function resetMessages() {
  error.value = ''
  smsNotice.value = ''
}

function validatePhone(phone: string) {
  return /^1\d{10}$/.test(phone.trim())
}

function validatePasswordForm() {
  if (!passwordForm.account.trim()) return '请输入账号'
  if (!passwordForm.password.trim()) return '请输入密码'
  return ''
}

function validateSmsForm(requireCode = false) {
  if (!validatePhone(smsForm.phone)) return '请输入正确的手机号'
  if (requireCode && !smsForm.code.trim()) return '请输入验证码'
  return ''
}

function startCountdown(seconds = 60) {
  countdown.value = seconds
  window.clearInterval(timer)
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) window.clearInterval(timer)
  }, 1000)
}

async function submitPasswordLogin() {
  const validationMessage = validatePasswordForm()
  if (validationMessage) {
    error.value = validationMessage
    return
  }

  loading.value = true
  resetMessages()
  try {
    await auth.login(passwordForm)
    await router.push(loginRedirect.value)
  } catch (e: any) {
    error.value = e?.message || '登录失败'
  } finally {
    loading.value = false
  }
}

async function sendSmsCode() {
  const validationMessage = validateSmsForm(false)
  if (validationMessage) {
    error.value = validationMessage
    return
  }
  if (countdown.value > 0) return

  smsSending.value = true
  resetMessages()
  try {
    await auth.smsSend({ phone: smsForm.phone.trim() })
    smsNotice.value = '验证码已发送，请查看短信'
    startCountdown(60)
  } catch (e: any) {
    error.value = e?.message || '验证码发送失败'
  } finally {
    smsSending.value = false
  }
}

async function submitSmsLogin() {
  const validationMessage = validateSmsForm(true)
  if (validationMessage) {
    error.value = validationMessage
    return
  }

  loading.value = true
  resetMessages()
  try {
    await auth.smsLogin({ phone: smsForm.phone.trim(), code: smsForm.code.trim() })
    await router.push(loginRedirect.value)
  } catch (e: any) {
    error.value = e?.message || '登录失败'
  } finally {
    loading.value = false
  }
}

function submitByEnter(event: KeyboardEvent) {
  if (event.key !== 'Enter') return
  event.preventDefault()
  if (mode.value === 'password') submitPasswordLogin()
  else submitSmsLogin()
}

function switchMode(next: 'password' | 'sms') {
  if (mode.value === next) return
  mode.value = next
  resetMessages()
}

onBeforeUnmount(() => {
  window.clearInterval(timer)
})
</script>

<template>
  <main class="page auth-page">
    <section class="auth-shell">
      <div class="auth-hero">
        <div class="auth-badge">Ink Marketplace</div>
        <p class="eyebrow">欢迎回来</p>
        <h1>回血集市</h1>
        <p class="muted">让闲置重新流动，也让每一次交易更透明、更安心。</p>
        <div class="auth-benefits">
          <span class="chip">真实好物</span>
          <span class="chip">同城交易</span>
          <span class="chip">AI 发布检查</span>
          <span class="chip">订单跟踪</span>
        </div>
      </div>

      <section class="hero-card auth-card" @keydown="submitByEnter">
        <div class="auth-header">
          <div>
            <p class="eyebrow">账户入口</p>
            <h2>{{ title }}</h2>
            <p class="muted">{{ subtitle }}</p>
          </div>
        </div>

        <div class="auth-tabs">
          <button :class="['tab-pill', { active: mode === 'password' }]" type="button" @click="switchMode('password')">账号密码</button>
          <button :class="['tab-pill', { active: mode === 'sms' }]" type="button" @click="switchMode('sms')">短信登录</button>
        </div>

        <template v-if="mode === 'password'">
          <label>账号<input v-model="passwordForm.account" autocomplete="username" placeholder="请输入账号" /></label>
          <label>
            密码
            <div class="password-field">
              <input v-model="passwordForm.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" />
              <button class="ghost-btn" type="button" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? '隐藏' : '显示' }}</button>
            </div>
          </label>
          <button class="primary-btn full-width" type="button" :disabled="loading" @click="submitPasswordLogin">{{ loading ? '登录中...' : '登录' }}</button>
        </template>

        <template v-else>
          <label>手机号<input v-model="smsForm.phone" inputmode="tel" autocomplete="tel" placeholder="请输入手机号" /></label>
          <div class="inline-actions">
            <button class="secondary-btn" type="button" :disabled="smsSending || loading || countdown > 0" @click="sendSmsCode">{{ sendButtonText }}</button>
            <span class="hint">验证码用于完成短信登录</span>
          </div>
          <label>验证码<input v-model="smsForm.code" inputmode="numeric" maxlength="6" placeholder="请输入验证码" /></label>
          <button class="primary-btn full-width" type="button" :disabled="loading" @click="submitSmsLogin">{{ loading ? '登录中...' : '短信登录' }}</button>
        </template>

        <p v-if="smsNotice" class="success-msg">{{ smsNotice }}</p>
        <p v-if="error" class="error">{{ error }}</p>

        <div class="auth-footer">
          <router-link to="/register">没有账号？去注册</router-link>
        </div>
      </section>
    </section>
  </main>
</template>
