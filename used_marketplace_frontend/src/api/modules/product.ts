import { get, post } from '../request'
import type { ProductDetail } from '../../types/market'

function toQuery(params: Record<string, any>) {
  return new URLSearchParams(Object.entries(params).reduce<Record<string, string>>((acc, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') acc[key] = String(value)
    return acc
  }, {})).toString()
}

export const productApi = {
  queryProduct: (params: Record<string, any>) => get(`/api/products/queryProduct?${toQuery(params)}`),
  getProductDetail: (productId: number | string): Promise<ProductDetail> => get(`/api/products/${productId}`),
  createProduct: (payload: any) => post('/api/products/createProduct', payload),
  uploadFile: (file: File): Promise<{ url?: string; fileUrl?: string; path?: string }> => {
    const formData = new FormData()
    formData.append('file', file)
    return post('/api/files/upload', formData)
  },
}
