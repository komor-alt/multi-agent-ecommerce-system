import { Injectable } from "@nestjs/common";
import { SettingsRepository } from "./settings.repository";

@Injectable()
export class SettingsService {
  constructor(private readonly repository: SettingsRepository) {}

  list() {
    void this.repository;
    return { items: [], page: 1, pageSize: 20, total: 0 };
  }
}
