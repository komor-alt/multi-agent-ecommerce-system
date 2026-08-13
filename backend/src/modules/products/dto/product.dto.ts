export type ProductListQueryDto = {
  page?: number;
  pageSize?: number;
  keyword?: string;
  category?: string;
  stockState?: "in_stock" | "low_stock" | "out_of_stock";
  status?: "active" | "inactive" | "archived";
  embeddingStatus?: "pending" | "ready" | "failed";
  sortBy?: "price" | "stock" | "updatedAt";
  sortOrder?: "asc" | "desc";
};

export type ProductDto = {
  productId: string;
  sku?: string;
  name: string;
  category: string;
  brand?: string;
  sellerId?: string;
  price: number;
  currency: string;
  stock: number;
  tags: string[];
  status: string;
  knowledgeStatus: string;
  embeddingStatus: string;
  updatedAt: string;
};
