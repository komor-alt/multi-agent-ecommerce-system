import { Injectable } from "@nestjs/common";
import { EvaluationsRepository } from "./evaluations.repository";

@Injectable()
export class EvaluationsService {
  constructor(private readonly repository: EvaluationsRepository) {}

  list() {
    void this.repository;
    return { items: [], page: 1, pageSize: 20, total: 0 };
  }
}
