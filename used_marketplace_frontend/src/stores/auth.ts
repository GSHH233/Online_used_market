import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '../api/modules/auth'

const TOKEN_KEY = 'market_token'
const ROLE_KEY = 'market_role'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const role = ref<string>(localStorage.getItem(ROLE_KEY) || 'USER')
  const isLoggedIn = computed(() => Boolean(token.value))
  const normalizedRole = computed(() => normalizeRole(role.value))
  const isInternal = computed(() => ['ADMIN', 'INTERNAL'].includes(normalizedRole.value))
  const isAdmin = computed(() => normalizedRole.value === 'ADMIN')

  function setToken(next: string) {
    token.value = next
    if (next) localStorage.setItem(TOKEN_KEY, next)
    else localStorage.removeItem(TOKEN_KEY)
  }

  function setRole(next?: string) {
    role.value = normalizeRole(next)
    localStorage.setItem(ROLE_KEY, role.value)
  }

  function logout() {
    setToken('')
    localStorage.removeItem(ROLE_KEY)
    role.value = 'USER'
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('market:unauthorized', logout)
  }

  async function login(payload: { account: string; password: string; loginType: string }) {
    const data = await authApi.login(payload)
    setToken((data as any).token || (data as any).jwtToken || '')
    setRole((data as any).role)
    return data
  }

  async function smsSend(payload: { phone: string }) {
    return authApi.smsSend(payload)
  }

  async function smsLogin(payload: { phone: string; code: string }) {
    const data = await authApi.smsLogin(payload)
    setToken((data as any).token || (data as any).jwtToken || '')
    setRole((data as any).role)
    return data
  }

  async function register(payload: { username: string; password: string; confirmPassword: string; nickname: string; phone: string }) {
    return authApi.register(payload)
  }

  return { token, role, normalizedRole, isInternal, isAdmin, isLoggedIn, login, smsSend, smsLogin, register, logout }
})

function normalizeRole(value?: string) {
  const role = String(value || 'USER').trim().toUpperCase()
  if (role === 'ADMIN' || role === 'INTERNAL') return role
  return 'USER'
}
