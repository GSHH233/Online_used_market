<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const form = reactive({ username: '', password: '', confirmPassword: '', nickname: '', phone: '' })
const loading = ref(false)
const msg = ref('')
const auth = useAuthStore()
const router = useRouter()

async function submit() {
  loading.value = true
  msg.value = ''
  try {
    await auth.register(form)
    msg.value = '注册成功，请登录'
    setTimeout(() => router.push('/login'), 700)
  } catch (e: any) {
    msg.value = e?.message || '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="page auth-page">
    <section class="auth-shell auth-shell--single">
      <section class="hero-card auth-card">
        <div class="auth-header">
          <div>
            <p class="eyebrow">创建账号</p>
            <h2>加入回血集市</h2>
            <p class="muted">注册后可以发布商品、收藏商品，并使用 AI 检查发布风险。</p>
          </div>
        </div>

        <div class="form-grid">
          <label>用户名<input v-model="form.username" autocomplete="username" placeholder="请输入用户名" /></label>
          <label>昵称<input v-model="form.nickname" autocomplete="nickname" placeholder="请输入昵称" /></label>
          <label>手机号<input v-model="form.phone" inputmode="tel" autocomplete="tel" placeholder="请输入手机号" /></label>
          <label>密码<input v-model="form.password" type="password" autocomplete="new-password" placeholder="请输入密码" /></label>
          <label>确认密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" /></label>
        </div>

        <button class="primary-btn full-width" type="button" :disabled="loading" @click="submit">{{ loading ? '提交中...' : '注册' }}</button>
        <p v-if="msg" :class="msg.includes('成功') ? 'success-msg' : 'error'">{{ msg }}</p>
        <div class="auth-footer">
          <router-link to="/login">返回登录</router-link>
        </div>
      </section>
    </section>
  </main>
</template>
