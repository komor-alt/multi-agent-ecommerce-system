# Agent Run SSE Event Contract

Frontend connects only to NestJS:

```text
GET /api/v1/agent-runs/:runId/stream
```

Java Recommendation Agent events are normalized by NestJS before storage and push.

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
- Client sends the last SSE event ID in `Last-Event-ID`; Gateway also accepts `?lastEventId=` for clients that cannot set headers.
- Gateway resolves the event ID to its run-local sequence and replays only later events from PostgreSQL.
- Gateway subscribes to live delivery before reading history, buffers events during the query, and de-duplicates the merge by sequence/event ID.
- Heartbeat events keep long-running connections alive.
- A terminal run replays its remaining events and closes the stream instead of retaining an idle heartbeat subscription.
- The frontend may still de-duplicate `eventId` defensively.
