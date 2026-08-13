import type { PageResult, ProductCategory, ProductDetail, ProductSummary } from "../types/contracts";
import { apiGet } from "./client";

export type ProductListParams = {
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

export function listProducts(params: ProductListParams = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return apiGet<PageResult<ProductSummary>>(`/products${suffix}`);
}

export function getProduct(productId: string) {
  return apiGet<ProductDetail>(`/products/${encodeURIComponent(productId)}`);
}

export function listProductCategories() {
  return apiGet<{ items: ProductCategory[] }>("/products/categories");
}
