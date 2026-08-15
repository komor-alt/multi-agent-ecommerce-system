import { Alert, Card, Col, Descriptions, Empty, Row, Space, Tag, Timeline, Typography } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { getAgentRun, getAgentRunEvents } from "../../api/runs";
import { createAgentRunEventSource, parseAgentRunSseMessage } from "../../hooks/useAgentRunSse";
import type { AgentRunSseEvent } from "../../types/contracts";

const sseEventTypes = [
  "run_started",
  "model_started",
  "model_completed",
  "tool_started",
  "tool_completed",
  "retrieval_completed",
  "warning",
  "error",
  "run_completed",
];

export function RunDetailPage() {
  const { runId = "" } = useParams();
  const queryClient = useQueryClient();
  const [liveEvents, setLiveEvents] = useState<AgentRunSseEvent[]>([]);
  const [sseError, setSseError] = useState("");
  const runQuery = useQuery({ queryKey: ["agent-run", runId], queryFn: () => getAgentRun(runId), enabled: Boolean(runId) });
  const eventsQuery = useQuery({ queryKey: ["agent-run-events", runId], queryFn: () => getAgentRunEvents(runId), enabled: Boolean(runId) });
  const run = runQuery.data;

  useEffect(() => {
    if (!runId) return;
    setLiveEvents([]);
    setSseError("");
    const source = createAgentRunEventSource(runId);

    const handleEvent = (event: Event) => {
      try {
        const parsed = parseAgentRunSseMessage(event as MessageEvent<string>);
        if (!parsed) return;
        setLiveEvents((current) => appendUniqueEvent(current, parsed));
        if (parsed.type === "run_completed") {
          void queryClient.invalidateQueries({ queryKey: ["agent-run", runId] });
          source.close();
        }
      } catch (error) {
        setSseError(error instanceof Error ? error.message : String(error));
      }
    };

    source.onmessage = handleEvent;
    for (const type of sseEventTypes) source.addEventListener(type, handleEvent);
    source.onerror = () => setSseError("SSE 连接失败或已断开；页面仍会展示数据库中已保存的历史事件。");

    return () => source.close();
  }, [queryClient, runId]);

  const events = useMemo(() => {
    const history = eventsQuery.data?.items || [];
    return [...history, ...liveEvents].reduce<AgentRunSseEvent[]>((acc, item) => appendUniqueEvent(acc, item), []);
  }, [eventsQuery.data?.items, liveEvents]);

  return (
    <div className="page-stack">
      <div>
        <Typography.Title level={3}>Agent Run Detail</Typography.Title>
        <Typography.Text type="secondary">Run ID: {runId}</Typography.Text>
      </div>
      {runQuery.error ? <Alert type="error" showIcon message="Run Detail 暂不可用" description={(runQuery.error as Error).message} /> : null}
      {sseError ? <Alert type="warning" showIcon message="SSE 状态" description={sseError} /> : null}
      <Row gutter={[16, 16]}>
        <Col span={16}>
          <Card title="Agent 执行时间线" loading={eventsQuery.isLoading}>
            {events.length ? (
              <Timeline
                items={events.map((event) => ({
                  color: event.status === "failed" ? "red" : event.status === "running" ? "blue" : "green",
                  children: (
                    <div className="event-line">
                      <Space>
                        <Typography.Text strong>{event.name}</Typography.Text>
                        <Tag>{event.type}</Tag>
                        <Typography.Text type="secondary">#{event.sequence}</Typography.Text>
                      </Space>
                      <div className="event-line-summary">{extractEventSummary(event.data)}</div>
                    </div>
                  ),
                }))}
              />
            ) : <Empty description="暂无事件。请先通过 Gateway 创建推荐任务。" />}
          </Card>
        </Col>
        <Col span={8}>
          <Card title="Run 基本信息" loading={runQuery.isLoading}>
            <Descriptions
              column={1}
              size="small"
              items={[
                { key: "status", label: "状态", children: run ? <Tag color={run.status === "completed" ? "success" : run.status === "failed" ? "error" : "processing"}>{run.status}</Tag> : "-" },
                { key: "scene", label: "场景", children: run?.scene || "-" },
                { key: "user", label: "用户", children: run?.userId || "-" },
                { key: "model", label: "模型", children: run?.modelName || "-" },
                { key: "latency", label: "延迟", children: run?.latencyMs == null ? "-" : `${run.latencyMs} ms` },
                { key: "tokens", label: "Token", children: run?.totalTokens ?? "-" },
                { key: "steps", label: "事件数", children: events.length },
              ]}
            />
          </Card>
        </Col>
      </Row>
      <Card title="最终 RecommendationPlan" loading={runQuery.isLoading}>
        {run?.finalAnswer ? <pre className="json-view">{JSON.stringify(run.finalAnswer, null, 2)}</pre> : <Empty description="等待 agent_runs.response_payload" />}
      </Card>
    </div>
  );
}

function appendUniqueEvent(events: AgentRunSseEvent[], next: AgentRunSseEvent) {
  if (events.some((item) => item.eventId === next.eventId || item.sequence === next.sequence)) return events;
  return [...events, next].sort((a, b) => a.sequence - b.sequence);
}

function extractEventSummary(data: Record<string, unknown>) {
  const summary = data.summary;
  if (typeof summary === "string") return summary;
  const rawPayload = data as { data?: { summary?: unknown } };
  if (typeof rawPayload.data?.summary === "string") return rawPayload.data.summary;
  return "-";
}
