import { del, get, post } from '../request'

function toQuery(params: Record<string, any>) {
  return new URLSearchParams(Object.entries(params).reduce<Record<string, string>>((acc, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') acc[key] = String(value)
    return acc
  }, {})).toString()
}

export const favoriteApi = {
  listFavorites: (params: Record<string, any>) => get(`/api/favorites?${toQuery(params)}`),
  addFavorite: (productId: number | string) => post(`/api/favorites/${productId}`),
  removeFavorite: (productId: number | string) => del(`/api/favorites/${productId}`),
}
