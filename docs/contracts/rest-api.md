# REST API Contract

Base path: `/api/v1`

All normal JSON responses use:

```json
{
  "success": true,
  "data": {},
  "requestId": "uuid"
}
```

All errors use:

```json
{
  "success": false,
  "error": {
    "code": "AGENT_RUN_NOT_FOUND",
    "message": "Agent run not found",
    "details": {}
  },
  "requestId": "uuid"
}
```

## Recommendation Tasks

| Method | Path | Purpose | Role |
| --- | --- | --- | --- |
| POST | `/recommendation-tasks` | Create a recommendation task and its Agent Run | operator/admin |
| GET | `/recommendation-tasks` | List recommendation tasks | viewer+ |
| GET | `/recommendation-tasks/:id` | Get task detail | viewer+ |

## Agent Runs

| Method | Path | Purpose | Role |
| --- | --- | --- | --- |
| GET | `/agent-runs` | Paginated run list with status/type/time filters | viewer+ |
| GET | `/agent-runs/:runId` | Run detail, final answer, metrics and review | viewer+ |
| GET | `/agent-runs/:runId/events` | Historical events loaded from database | viewer+ |
| GET | `/agent-runs/:runId/stream` | SSE stream proxied by NestJS | viewer+ |
| POST | `/agent-runs/:runId/reviews` | Approve or reject high-risk result | operator/admin |

## Products

| Method | Path | Purpose | Role |
| --- | --- | --- | --- |
| GET | `/products` | Search, filter and page products | viewer+ |
| GET | `/products/:productId` | Product detail and knowledge status | viewer+ |
| PATCH | `/products/:productId` | Update product business fields | admin |

## Evaluations

| Method | Path | Purpose | Role |
| --- | --- | --- | --- |
| GET | `/evaluations/datasets` | List evaluation datasets | viewer+ |
| POST | `/evaluations/runs` | Start an evaluation run | operator/admin |
| GET | `/evaluations/runs/:id` | Evaluation run detail and failed cases | viewer+ |

## Settings

| Method | Path | Purpose | Role |
| --- | --- | --- | --- |
| GET | `/settings` | Read model, tool and RAG configuration | viewer+ |
| PATCH | `/settings` | Update configuration with audit log | admin |
