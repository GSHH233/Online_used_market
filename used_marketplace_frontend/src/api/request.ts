import axios, {
  AxiosError,
  type AxiosProgressEvent,
  type AxiosRequestConfig,
} from 'axios'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''
const REQUEST_TIMEOUT_MS = 15000
const TOKEN_KEY = 'market_token'

type ApiResp<T> = { code: number; message: string; data: T }

export class ApiError extends Error {
  status?: number
  code?: string
  raw?: unknown

  constructor(message: string, options?: { status?: number; code?: string; raw?: unknown }) {
    super(message)
    this.name = 'ApiError'
    this.status = options?.status
    this.code = options?.code
    this.raw = options?.raw
  }
}

const http = axios.create({
  baseURL: API_BASE_URL || undefined,
  timeout: REQUEST_TIMEOUT_MS,
  withCredentials: true,
  headers: {
    Accept: 'application/json, text/plain, */*',
  },
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY) || ''
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function unwrap<T>(payload: unknown, status?: number): T {
  if (payload && typeof payload === 'object' && 'code' in payload && 'data' in payload) {
    const res = payload as ApiResp<T>
    if (res.code !== 200) {
      throw new ApiError(res.message || '请求失败', { status, raw: payload })
    }
    return res.data
  }
  return payload as T
}

function normalizeError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError
    const status = axiosError.response?.status
    const raw = axiosError.response?.data

    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem('market_role')
      window.dispatchEvent(new CustomEvent('market:unauthorized'))
      return new ApiError('登录已过期，请重新登录', { status, raw })
    }

    if (axiosError.code === 'ECONNABORTED') {
      return new ApiError('请求超时，请稍后重试', { code: axiosError.code, raw })
    }

    if (axiosError.message === 'Network Error') {
      return new ApiError('网络异常，请检查后端服务或跨域配置', { code: 'NETWORK_ERROR', raw: error })
    }

    if (raw && typeof raw === 'object' && 'message' in raw) {
      return new ApiError(String((raw as { message?: unknown }).message || '请求失败'), { status, raw })
    }

    return new ApiError(axiosError.message || '请求失败', { status, code: axiosError.code, raw })
  }

  const message = error instanceof Error ? error.message : '未知请求错误'
  return new ApiError(message, { raw: error })
}

export async function request<T>(path: string, config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await http.request({
      url: path,
      ...config,
    })
    return unwrap<T>(response.data, response.status)
  } catch (error) {
    throw normalizeError(error)
  }
}

export function post<T>(path: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>(path, { ...config, method: 'POST', data: body })
}

export function get<T>(path: string, config?: AxiosRequestConfig): Promise<T> {
  return request<T>(path, { ...config, method: 'GET' })
}

export function del<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' })
}

export function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'PUT', data: body })
}

export function patch<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'PATCH', data: body })
}

export function upload<T>(
  path: string,
  formData: FormData,
  options?: {
    signal?: AbortSignal
    onProgress?: (percent: number, event: AxiosProgressEvent) => void
  },
): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    data: formData,
    signal: options?.signal,
    onUploadProgress: (event) => {
      if (!options?.onProgress) return
      const percent = event.total
        ? Math.min(100, Math.round((event.loaded * 100) / event.total))
        : 0
      options.onProgress(percent, event)
    },
  })
}

export function buildApiUrl(path: string) {
  if (path.startsWith('http://') || path.startsWith('https://')) return path
  if (!path.startsWith('/')) return `${API_BASE_URL}/${path}`
  return `${API_BASE_URL}${path}`
}

export function buildWebSocketUrl(path: string) {
  const httpUrl = buildApiUrl(path)
  if (httpUrl.startsWith('http://') || httpUrl.startsWith('https://')) {
    return httpUrl.replace(/^http/, 'ws')
  }
  const normalizedPath = httpUrl.startsWith('/') ? httpUrl : `/${httpUrl}`
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.host}${normalizedPath}`
}
