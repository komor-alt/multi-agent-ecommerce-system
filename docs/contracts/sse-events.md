# Agent Run SSE Event Contract

Frontend connects only to NestJS:

```text
GET /api/v1/agent-runs/:runId/stream
```

Python Agent Service events are normalized by NestJS before storage and push.

```json
{
  "eventId": "uuid",
  "runId": "uuid",
  "sequence": 1,
  "type": "tool_completed",
  "name": "search_products",
  "status": "success",
  "timestamp": "2026-07-30T12:00:00.000Z",
  "data": {},
  "metrics": {
    "latencyMs": 320,
    "inputTokens": 0,
    "outputTokens": 0,
    "totalTokens": 0
  }
}
```

## Event Types

- `run_started`
- `model_started`
- `model_completed`
- `tool_started`
- `tool_completed`
- `retrieval_completed`
- `warning`
- `error`
- `run_completed`
- `heartbeat`

## Reliability Rules

- `sequence` is unique within one `runId`.
- Client sends `Last-Event-ID` on reconnect.
- Server replays events with sequence greater than the last acknowledged event.
- Heartbeat events keep long-running connections alive.
- Duplicate `eventId` values are ignored by the frontend.
- Completed runs are loaded from `/agent-runs/:runId/events` before opening SSE.
