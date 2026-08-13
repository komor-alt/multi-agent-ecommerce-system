import { Injectable } from "@nestjs/common";
import { OrdersRepository } from "./orders.repository";

@Injectable()
export class OrdersService {
  constructor(private readonly repository: OrdersRepository) {}

  list() {
    void this.repository;
    return { items: [], page: 1, pageSize: 20, total: 0 };
  }
}
