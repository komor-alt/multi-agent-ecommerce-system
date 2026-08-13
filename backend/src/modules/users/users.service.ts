import { Injectable } from "@nestjs/common";
import { UsersRepository } from "./users.repository";

@Injectable()
export class UsersService {
  constructor(private readonly repository: UsersRepository) {}

  list() {
    void this.repository;
    return { items: [], page: 1, pageSize: 20, total: 0 };
  }
}
