import { Injectable } from "@nestjs/common";
import type { Product } from "@prisma/client";
import { ProductsRepository } from "./products.repository";
import type { ProductDto, ProductListQueryDto } from "./dto/product.dto";

@Injectable()
export class ProductsService {
  constructor(private readonly repository: ProductsRepository) {}

  async list(query: ProductListQueryDto = {}) {
    const result = await this.repository.list(query);
    return {
      ...result,
      items: result.items.map((product) => this.toDto(product)),
    };
  }

  async detail(productId: string) {
    const product = await this.repository.findByProductId(productId);
    if (!product) return null;
    return {
      ...this.toDto(product),
      description: product.description,
      metadata: product.metadata,
      createdAt: product.createdAt.toISOString(),
    };
  }

  async categories() {
    return { items: await this.repository.categories() };
  }

  private toDto(product: Product): ProductDto {
    return {
      productId: product.productId,
      sku: product.sku || undefined,
      name: product.name,
      category: product.category,
      brand: product.brand || undefined,
      sellerId: product.sellerId || undefined,
      price: Number(product.price),
      currency: product.currency,
      stock: product.stock,
      tags: product.tags,
      status: product.status.toLowerCase(),
      knowledgeStatus: product.knowledgeStatus.toLowerCase(),
      embeddingStatus: product.embeddingStatus.toLowerCase(),
      updatedAt: product.updatedAt.toISOString(),
    };
  }
}
