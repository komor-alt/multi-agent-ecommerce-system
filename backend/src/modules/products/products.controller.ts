import { Controller, Get, NotFoundException, Param, Query } from "@nestjs/common";
import { ProductsService } from "./products.service";
import type { ProductListQueryDto } from "./dto/product.dto";

@Controller("products")
export class ProductsController {
  constructor(private readonly service: ProductsService) {}

  @Get()
  async list(@Query() query: ProductListQueryDto) {
    return { success: true, data: await this.service.list(query), requestId: "local-products-list" };
  }

  @Get("categories")
  async categories() {
    return { success: true, data: await this.service.categories(), requestId: "local-product-categories" };
  }

  @Get(":productId")
  async detail(@Param("productId") productId: string) {
    const data = await this.service.detail(productId);
    if (!data) throw new NotFoundException("Product not found");
    return { success: true, data, requestId: productId };
  }
}
