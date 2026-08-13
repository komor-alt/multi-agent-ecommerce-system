import { Injectable } from "@nestjs/common";
import { EmbeddingStatus, Prisma, ProductStatus } from "@prisma/client";
import { PrismaService } from "../../infrastructure/database/prisma.service";
import type { ProductListQueryDto } from "./dto/product.dto";

@Injectable()
export class ProductsRepository {
  constructor(private readonly prisma: PrismaService) {}

  async list(query: ProductListQueryDto = {}) {
    const page = Math.max(Number(query.page || 1), 1);
    const pageSize = Math.min(Math.max(Number(query.pageSize || 20), 1), 100);
    const where = this.buildWhere(query);
    const orderBy = this.buildOrder(query);

    const [items, total] = await this.prisma.$transaction([
      this.prisma.product.findMany({
        where,
        orderBy,
        skip: (page - 1) * pageSize,
        take: pageSize,
      }),
      this.prisma.product.count({ where }),
    ]);

    return { items, page, pageSize, total };
  }

  findByProductId(productId: string) {
    return this.prisma.product.findUnique({ where: { productId } });
  }

  async categories() {
    const rows = await this.prisma.product.groupBy({
      by: ["category"],
      orderBy: { category: "asc" },
      _count: { category: true },
    });
    return rows.map((row) => ({ category: row.category, count: row._count.category }));
  }

  private buildWhere(query: ProductListQueryDto): Prisma.ProductWhereInput {
    const where: Prisma.ProductWhereInput = {};

    if (query.keyword) {
      where.OR = [
        { productId: { contains: query.keyword, mode: "insensitive" } },
        { sku: { contains: query.keyword, mode: "insensitive" } },
        { name: { contains: query.keyword, mode: "insensitive" } },
        { brand: { contains: query.keyword, mode: "insensitive" } },
      ];
    }

    if (query.category) where.category = query.category;
    if (query.status) where.status = query.status.toUpperCase() as ProductStatus;
    if (query.embeddingStatus) where.embeddingStatus = query.embeddingStatus.toUpperCase() as EmbeddingStatus;
    if (query.stockState === "in_stock") where.stock = { gt: 20 };
    if (query.stockState === "low_stock") where.stock = { gt: 0, lte: 20 };
    if (query.stockState === "out_of_stock") where.stock = 0;

    return where;
  }

  private buildOrder(query: ProductListQueryDto): Prisma.ProductOrderByWithRelationInput {
    const direction = query.sortOrder || "desc";
    if (query.sortBy === "price") return { price: direction };
    if (query.sortBy === "stock") return { stock: direction };
    return { updatedAt: direction };
  }
}
