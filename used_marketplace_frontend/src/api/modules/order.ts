import { get, post, put } from '../request'

function toQuery(params: Record<string, any>) {
  return new URLSearchParams(Object.entries(params).reduce<Record<string, string>>((acc, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') acc[key] = String(value)
    return acc
  }, {})).toString()
}

export const orderApi = {
  listOrders: (params: Record<string, any>) => get(`/api/orders?${toQuery(params)}`),
  getOrderDetail: (orderId: number | string) => get(`/api/orders/${orderId}`),
  createOrder: (payload: any) => post('/api/orders', payload),
  cancelOrder: (orderId: number | string) => put(`/api/orders/${orderId}/cancel`),
  deliverOrder: (orderId: number | string) => put(`/api/orders/${orderId}/deliver`),
  finishOrder: (orderId: number | string) => put(`/api/orders/${orderId}/finish`),
  payOrder: (orderId: number | string) => put(`/api/orders/${orderId}/pay`),
}
