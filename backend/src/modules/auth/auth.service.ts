import { Injectable } from "@nestjs/common";
import { AuthRepository } from "./auth.repository";

@Injectable()
export class AuthService {
  constructor(private readonly repository: AuthRepository) {}

  list() {
    void this.repository;
    return { items: [], page: 1, pageSize: 20, total: 0 };
  }
}
