export type ApiResponse<T> = {
  code: number
  message: string
  data: T
}

export type PaginationQuery = {
  limit?: number
  topK?: number
}
