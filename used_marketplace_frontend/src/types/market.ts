export type ProductListItem = {
  id: number
  sellerId?: number
  categoryId?: number
  title: string
  price: number | string
  conditionLevel?: number
  locationText?: string
  viewCount?: number
  favoriteCount?: number
  status?: number | string
  publishTime?: string
  mainImageUrl?: string
}

export type ProductDetail = ProductListItem & {
  description?: string
  originalPrice?: number | string
  createdTime?: string
  imageUrls?: string[]
  sellerNickname?: string
  sellerAvatar?: string
  sellerCreatedTime?: string
}
