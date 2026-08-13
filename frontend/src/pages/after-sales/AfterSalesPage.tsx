import {
  AuditOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CustomerServiceOutlined,
  FileSearchOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Empty,
  Form,
  Input,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Steps,
  Tag,
  Timeline,
  Typography,
} from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  analyzeAfterSalesTicket,
  approveAfterSalesProposal,
  createAfterSalesEventSource,
  createAfterSalesTicket,
  getAfterSalesTicket,
  parseAfterSalesEvent,
  rejectAfterSalesProposal,
  retryAfterSalesExecution,
  startAfterSalesRun,
} from "../../api/afterSales";
import type { AfterSalesEvent, AfterSalesTicket } from "../../types/afterSales";
import { formatDuration } from "../../utils/format";

const streamEventTypes = [
  "run_started",
  "tool_started",
  "tool_completed",
  "retrieval_completed",
  "run_completed",
  "error",
  "approval_recorded",
  "execution_started",
  "execution_completed",
  "execution_failed",
];

const demoMessage = "我的包裹十天没有更新了，现在到底是什么情况？能不能退款？";

export function AfterSalesPage() {
  const queryClient = useQueryClient();
  const [orderId, setOrderId] = useState("O-VN-5002");
  const [customerMessage, setCustomerMessage] = useState(demoMessage);
  const [operatorId, setOperatorId] = useState("operator-vn-01");
  const [reviewComment, setReviewComment] = useState("订单与政策证据已核验，同意发放延迟补偿券。");
  const [ticketId, setTicketId] = useState("");
  const [runId, setRunId] = useState("");
  const [liveEvents, setLiveEvents] = useState<AfterSalesEvent[]>([]);
  const [streamStatus, setStreamStatus] = useState<"idle" | "connecting" | "live" | "closed" | "error">("idle");
  const [analysisStartedAt, setAnalysisStartedAt] = useState<number | null>(null);
  const [tickNow, setTickNow] = useState(0);
  const [backendDurationMs, setBackendDurationMs] = useState<number | null>(null);
  // 「先订阅、后启动」：stream_ready 握手后调用 start；重连/重试通过 epoch 重建 EventSource。
  const [streamEpoch, setStreamEpoch] = useState(0);
  const [startError, setStartError] = useState<string | null>(null);
  const sourceRef = useRef<EventSource | null>(null);
  const lastEventIdRef = useRef<string | undefined>(undefined);
  const closedRef = useRef(false);
  const reconnectAttemptsRef = useRef(0);

  const ticketQuery = useQuery({
    queryKey: ["after-sales-ticket", ticketId],
    queryFn: () => getAfterSalesTicket(ticketId),
    enabled: Boolean(ticketId),
  });

  const ticket = ticketQuery.data;

  // 后端 durationMs（run_completed / error 事件或工单详情 run 对象）优先；
  // 否则在分析期间用本地时钟实时递增估算。
  const finalDurationMs = backendDurationMs ?? ticket?.run?.durationMs ?? null;
  const liveDurationMs = analysisStartedAt != null ? tickNow - analysisStartedAt : null;
  const analysisDurationMs = finalDurationMs != null ? finalDurationMs : liveDurationMs;

  // 仅当「没有后端终值」且「分析仍在进行」时启动本地计时；
  // 收到终值或流结束（closed/error）后 cleanup 停止，避免泄漏。
  const isAnalyzing = streamStatus !== "closed" && streamStatus !== "error";
  useEffect(() => {
    if (analysisStartedAt == null || finalDurationMs != null || !isAnalyzing) return;
    const timer = window.setInterval(() => setTickNow(Date.now()), 200);
    return () => window.clearInterval(timer);
  }, [analysisStartedAt, finalDurationMs, isAnalyzing]);

  const createMutation = useMutation({
    mutationFn: async () => {
      const ticket = await createAfterSalesTicket({ orderId, customerMessage });
      // deferred=true：只创建 READY run 并进入 ANALYZING，等 stream_ready 握手后再启动。
      const analysis = await analyzeAfterSalesTicket(ticket.id, { deferred: true });
      return { ticket, analysis };
    },
    onSuccess: ({ ticket, analysis }) => {
      setTicketId(ticket.id);
      setRunId(analysis.runId);
      setLiveEvents([]);
      setStreamStatus("connecting");
      setAnalysisStartedAt(null);
      setBackendDurationMs(null);
      setTickNow(0);
      setStartError(null);
      closedRef.current = false;
      reconnectAttemptsRef.current = 0;
      lastEventIdRef.current = undefined;
      setStreamEpoch((epoch) => epoch + 1);
    },
  });

  // stream_ready 握手后调用 start；后端原子认领 READY->RUNNING，重连时重复调用是幂等的。
  const startRun = useCallback(async () => {
    try {
      await startAfterSalesRun(runId);
      setStartError(null);
    } catch (error) {
      setStartError(errorMessage(error));
      setStreamStatus("error");
      closedRef.current = true;
      sourceRef.current?.close();
      refreshTicket(ticketId, queryClient);
    }
  }, [queryClient, runId, ticketId]);

  const retryStart = () => {
    setStartError(null);
    closedRef.current = false;
    reconnectAttemptsRef.current = 0;
    setStreamStatus("connecting");
    setStreamEpoch((epoch) => epoch + 1);
  };

  const reviewMutation = useMutation({
    mutationFn: async (decision: "approve" | "reject") => {
      const proposalId = ticketQuery.data?.proposal?.id;
      if (!proposalId) throw new Error("当前没有可审批的方案");
      if (decision === "approve") {
        return approveAfterSalesProposal(proposalId, { operatorId, comment: reviewComment });
      }
      return rejectAfterSalesProposal(proposalId, { operatorId, comment: reviewComment });
    },
    onSuccess: () => refreshTicket(ticketId, queryClient),
  });

  const retryMutation = useMutation({
    mutationFn: (jobId: string) => retryAfterSalesExecution(jobId),
    onSuccess: () => refreshTicket(ticketId, queryClient),
  });

  useEffect(() => {
    if (!runId) return;
    // 重连时携带 Last-Event-ID 只回放缺失事件；服务端仍会先发 stream_ready，
    // 客户端再次调用 start —— 幂等，安全。
    const source = createAfterSalesEventSource(runId, lastEventIdRef.current);
    sourceRef.current = source;
    setStreamStatus("connecting");

    const handleEvent = (raw: Event) => {
      try {
        const event = parseAfterSalesEvent(raw as MessageEvent<string>);
        if (event.type === "stream_ready") {
          // 握手事件：只作「流已就绪、可以启动」的信号，不进入决策时间线。
          reconnectAttemptsRef.current = 0;
          setStreamStatus("live");
          void startRun();
          return;
        }
        if (event.eventId) lastEventIdRef.current = event.eventId;
        setStreamStatus("live");
        setLiveEvents((current) => appendEvent(current, event));
        if (event.type === "run_started") {
          // run_started 之后才开始本地计时。
          setAnalysisStartedAt(Date.now());
          setTickNow(Date.now());
          setBackendDurationMs(null);
        }
        if (typeof event.data.durationMs === "number") {
          setBackendDurationMs(event.data.durationMs);
        }
        if (event.type === "run_completed") {
          refreshTicket(ticketId, queryClient);
        }
        const rejected = event.type === "approval_recorded" && event.data.decision === "REJECTED";
        if (event.type === "execution_completed" || event.type === "error" || rejected) {
          closedRef.current = true;
          setStreamStatus("closed");
          source.close();
          refreshTicket(ticketId, queryClient);
        }
      } catch {
        setStreamStatus("error");
      }
    };

    source.onopen = () => setStreamStatus("live");
    source.onmessage = handleEvent;
    streamEventTypes.forEach((type) => source.addEventListener(type, handleEvent));
    source.addEventListener("stream_ready", handleEvent);
    source.onerror = () => {
      if (closedRef.current) return;
      const attempts = reconnectAttemptsRef.current;
      if (attempts >= 3) {
        closedRef.current = true;
        setStreamStatus("error");
        refreshTicket(ticketId, queryClient);
        return;
      }
      reconnectAttemptsRef.current = attempts + 1;
      setStreamStatus("connecting");
      window.setTimeout(() => setStreamEpoch((epoch) => epoch + 1), 600 * attempts + 600);
    };
    return () => {
      sourceRef.current = null;
      source.close();
    };
  }, [queryClient, runId, startRun, streamEpoch, ticketId]);

  const events = useMemo(
    () => [...(ticket?.events || []), ...liveEvents].reduce<AfterSalesEvent[]>(appendEvent, []),
    [liveEvents, ticket?.events],
  );
  const finalAnswer = ticket?.run?.finalAnswer;
  const proposal = ticket?.proposal;
  const execution = ticket?.executionJob;
  const isPendingApproval = proposal?.status === "PENDING";

  return (
    <div className="page-stack after-sales-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={3}>售后 Agent 工作台</Typography.Title>
          <Typography.Text type="secondary">跨境物流异常 · Vietnam Operations</Typography.Text>
        </div>
        <Space>
          <Badge status={streamStatus === "live" ? "processing" : streamStatus === "error" ? "error" : "default"} />
          <Typography.Text type="secondary">{streamLabel(streamStatus)}</Typography.Text>
        </Space>
      </div>

      {createMutation.error || ticketQuery.error || reviewMutation.error ? (
        <Alert
          showIcon
          type="error"
          message="售后链路暂不可用"
          description={errorMessage(createMutation.error || ticketQuery.error || reviewMutation.error)}
        />
      ) : null}

      {startError ? (
        <Alert
          showIcon
          type="error"
          message="启动 Agent 失败"
          description={startError}
          action={<Button size="small" danger onClick={retryStart}>重试启动</Button>}
        />
      ) : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={8}>
          <Card title="创建售后工单">
            <Form layout="vertical" onFinish={() => createMutation.mutate()}>
              <Form.Item label="演示订单">
                <Select
                  value={orderId}
                  onChange={setOrderId}
                  options={[
                    { value: "O-VN-5002", label: "O-VN-5002 · 越南跨境物流异常" },
                  ]}
                />
              </Form.Item>
              <Form.Item label="用户诉求">
                <Input.TextArea
                  value={customerMessage}
                  onChange={(event) => setCustomerMessage(event.target.value)}
                  rows={5}
                />
              </Form.Item>
              <Button
                block
                type="primary"
                htmlType="submit"
                icon={<CustomerServiceOutlined />}
                loading={createMutation.isPending}
              >
                创建并分析
              </Button>
            </Form>
          </Card>
        </Col>

        <Col xs={24} xl={16}>
          <Row gutter={[12, 12]}>
            <Col xs={12} md={8} lg={{ flex: "20%" }}>
              <Card size="small"><Statistic title="工单状态" value={ticketStatusLabel(ticket?.status)} /></Card>
            </Col>
            <Col xs={12} md={8} lg={{ flex: "20%" }}>
              <Card size="small"><Statistic title="执行步骤" value={ticket?.run?.stepCount || events.filter((item) => item.type === "tool_completed" || item.type === "retrieval_completed").length} suffix="/ 5" /></Card>
            </Col>
            <Col xs={12} md={8} lg={{ flex: "20%" }}>
              <Card size="small"><Statistic title="证据数量" value={finalAnswer?.evidenceIds?.length || proposal?.evidenceIds?.length || 0} /></Card>
            </Col>
            <Col xs={12} md={8} lg={{ flex: "20%" }}>
              <Card size="small"><Statistic title="执行状态" value={executionStatusLabel(execution?.status)} /></Card>
            </Col>
            <Col xs={12} md={8} lg={{ flex: "20%" }}>
              <Card size="small" className="duration-stat">
                <Statistic
                  title="本次分析耗时"
                  value={analysisDurationMs == null ? "—" : formatDuration(analysisDurationMs)}
                />
              </Card>
            </Col>
          </Row>

          <Collapse
            className="decision-trace"
            defaultActiveKey={["trace"]}
            items={[{
              key: "trace",
              label: (
                <Space>
                  <AuditOutlined />
                  <Typography.Text strong>决策轨迹</Typography.Text>
                  <Tag color={streamStatus === "live" ? "processing" : ticket?.status === "FAILED" ? "error" : "default"}>
                    {events.length} 条事件
                  </Tag>
                </Space>
              ),
              children: <DecisionTimeline events={events} running={createMutation.isPending || ticket?.status === "ANALYZING"} />,
            }]}
          />
        </Col>
      </Row>

      <Steps
        size="small"
        current={workflowStep(ticket)}
        status={ticket?.status === "FAILED" ? "error" : "process"}
        items={[
          { title: "创建工单" },
          { title: "Agent 分析" },
          { title: "人工审批" },
          { title: "可靠执行" },
        ]}
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={8}>
          <Card title="订单与物流证据" extra={<FileSearchOutlined />} className="after-sales-detail-card">
            {finalAnswer?.order ? (
              <>
                <Descriptions
                  column={1}
                  size="small"
                  items={[
                    { key: "order", label: "订单", children: finalAnswer.order.orderId },
                    { key: "amount", label: "实付金额", children: formatMoney(finalAnswer.order.paidAmount, finalAnswer.order.currency) },
                    { key: "route", label: "履约路线", children: `${finalAnswer.order.warehouseRegion} → ${finalAnswer.order.country}` },
                    { key: "tracking", label: "运单号", children: finalAnswer.order.trackingNumber },
                    { key: "inactive", label: "未更新时间", children: `${finalAnswer.shipment.inactiveDays} 天` },
                  ]}
                />
                <Timeline
                  className="shipment-timeline"
                  items={finalAnswer.shipment.timeline.map((item) => ({
                    color: item.status === "CUSTOMS_DOCUMENT_REQUIRED" ? "orange" : "green",
                    children: (
                      <div>
                        <Typography.Text strong>{item.location}</Typography.Text>
                        <div className="event-line-summary">{item.description}</div>
                      </div>
                    ),
                  }))}
                />
              </>
            ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="等待订单与物流核验" />}
          </Card>
        </Col>

        <Col xs={24} xl={8}>
          <Card title="政策证据" extra={<SafetyCertificateOutlined />} className="after-sales-detail-card">
            {finalAnswer?.policy ? (
              <>
                <Descriptions
                  column={1}
                  size="small"
                  items={[
                    { key: "policy", label: "政策", children: finalAnswer.policy.policyId },
                    { key: "version", label: "版本", children: <Tag color="blue">{finalAnswer.policy.version}</Tag> },
                    { key: "section", label: "条款", children: finalAnswer.policy.section },
                    { key: "threshold", label: "触发阈值", children: `${finalAnswer.policy.minimumInactiveDays} 天未更新` },
                    { key: "rate", label: "补偿比例", children: `${Math.round(finalAnswer.policy.compensationRate * 100)}%` },
                  ]}
                />
                <Alert
                  type="info"
                  showIcon
                  message={finalAnswer.policy.summary}
                  description={<Typography.Text code>{finalAnswer.policy.evidenceId}</Typography.Text>}
                />
              </>
            ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="等待政策检索" />}
          </Card>
        </Col>

        <Col xs={24} xl={8}>
          <Card
            title="处理方案"
            extra={proposal ? <Tag color={proposalColor(proposal.status)}>{proposalStatusLabel(proposal.status)}</Tag> : null}
            className="after-sales-detail-card"
          >
            {proposal ? (
              <Space direction="vertical" size={14} style={{ width: "100%" }}>
                <div className="proposal-amount">
                  <Typography.Text type="secondary">延迟补偿券</Typography.Text>
                  <Typography.Title level={2}>{formatMoney(proposal.amount, proposal.currency)}</Typography.Title>
                </div>
                <Typography.Paragraph>{proposal.decisionSummary}</Typography.Paragraph>
                <Input
                  value={operatorId}
                  onChange={(event) => setOperatorId(event.target.value)}
                  addonBefore="审批人"
                  disabled={!isPendingApproval}
                />
                <Input.TextArea
                  value={reviewComment}
                  onChange={(event) => setReviewComment(event.target.value)}
                  rows={3}
                  disabled={!isPendingApproval}
                />
                {isPendingApproval ? (
                  <Space>
                    <Popconfirm
                      title="确认批准该补偿方案？"
                      description="批准后将创建唯一执行任务并发放补偿券。"
                      onConfirm={() => reviewMutation.mutate("approve")}
                    >
                      <Button type="primary" icon={<CheckCircleOutlined />} loading={reviewMutation.isPending}>批准并执行</Button>
                    </Popconfirm>
                    <Popconfirm
                      title="确认驳回该方案？"
                      onConfirm={() => reviewMutation.mutate("reject")}
                    >
                      <Button danger>驳回</Button>
                    </Popconfirm>
                  </Space>
                ) : null}
                {execution ? <ExecutionStatusBlock ticket={ticket} onRetry={() => retryMutation.mutate(execution.id)} /> : null}
              </Space>
            ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Agent 完成后生成待审批方案" />}
          </Card>
        </Col>
      </Row>
    </div>
  );
}

function DecisionTimeline({ events, running }: { events: AfterSalesEvent[]; running: boolean }) {
  if (!events.length) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={running ? "正在建立实时事件流" : "创建工单后展示结构化决策轨迹"} />;
  }
  return (
    <Timeline
      pending={running ? "Agent 正在执行下一步" : undefined}
      items={events.map((event) => ({
        color: event.status === "failed" ? "red" : event.status === "running" ? "blue" : "green",
        dot: event.status === "running" ? <SyncOutlined spin /> : undefined,
        children: (
          <div className="decision-event">
            <Space wrap>
              <Typography.Text strong>{event.name}</Typography.Text>
              <Tag>{actionLabel(event.data.action)}</Tag>
              <Typography.Text type="secondary">#{event.sequence}</Typography.Text>
            </Space>
            <div className="event-line-summary">{event.data.summary || "-"}</div>
            {isToolCompletion(event) && event.data.latencyMs != null ? (
              <Typography.Text type="secondary" className="event-metric">
                工具耗时 {formatDuration(event.data.latencyMs)}
              </Typography.Text>
            ) : null}
            {isRunTerminal(event) && event.data.durationMs != null ? (
              <Typography.Text type="secondary" className="event-metric">
                分析总耗时 {formatDuration(event.data.durationMs)}
              </Typography.Text>
            ) : null}
            {event.data.evidenceIds?.length ? (
              <Space wrap className="evidence-list">
                {event.data.evidenceIds.map((id) => <Typography.Text code key={id}>{id}</Typography.Text>)}
              </Space>
            ) : null}
          </div>
        ),
      }))}
    />
  );
}

function ExecutionStatusBlock({ ticket, onRetry }: { ticket?: AfterSalesTicket; onRetry: () => void }) {
  const execution = ticket?.executionJob;
  if (!execution) return null;
  const retryable = execution.status === "RETRY_WAIT" || execution.status === "DEAD_LETTER";
  return (
    <Alert
      type={execution.status === "SUCCEEDED" ? "success" : execution.status === "DEAD_LETTER" ? "error" : "info"}
      showIcon
      icon={execution.status === "SUCCEEDED" ? <CheckCircleOutlined /> : <ClockCircleOutlined />}
      message={`执行状态：${executionStatusLabel(execution.status)}`}
      description={(
        <Space direction="vertical" size={4}>
          <Typography.Text>尝试次数：{execution.attemptCount}</Typography.Text>
          {execution.result?.externalReference ? <Typography.Text code>{execution.result.externalReference}</Typography.Text> : null}
          {retryable ? <Button size="small" onClick={onRetry}>重新执行</Button> : null}
        </Space>
      )}
    />
  );
}

function isToolCompletion(event: AfterSalesEvent) {
  return event.type === "tool_completed" || event.type === "retrieval_completed";
}

function isRunTerminal(event: AfterSalesEvent) {
  return event.type === "run_completed" || event.type === "error";
}

function appendEvent(events: AfterSalesEvent[], next: AfterSalesEvent) {
  if (events.some((item) => item.eventId === next.eventId || item.sequence === next.sequence)) return events;
  return [...events, next].sort((a, b) => a.sequence - b.sequence);
}

function refreshTicket(ticketId: string, queryClient: ReturnType<typeof useQueryClient>) {
  if (!ticketId) return;
  void queryClient.invalidateQueries({ queryKey: ["after-sales-ticket", ticketId] });
  window.setTimeout(() => {
    void queryClient.invalidateQueries({ queryKey: ["after-sales-ticket", ticketId] });
  }, 900);
}

function workflowStep(ticket?: AfterSalesTicket) {
  if (!ticket) return 0;
  if (ticket.status === "OPEN" || ticket.status === "ANALYZING") return 1;
  if (ticket.status === "PENDING_APPROVAL") return 2;
  return 3;
}

function streamLabel(status: string) {
  if (status === "connecting") return "SSE 连接中";
  if (status === "live") return "SSE 实时";
  if (status === "closed") return "运行已结束";
  if (status === "error") return "SSE 已断开";
  return "等待运行";
}

function ticketStatusLabel(status?: string) {
  const labels: Record<string, string> = {
    OPEN: "待分析",
    ANALYZING: "分析中",
    PENDING_APPROVAL: "待审批",
    RESOLVED: "已完成",
    FAILED: "失败",
  };
  return status ? labels[status] || status : "未创建";
}

function proposalStatusLabel(status: string) {
  return ({ PENDING: "待审批", APPROVED: "已批准", REJECTED: "已驳回" } as Record<string, string>)[status] || status;
}

function proposalColor(status: string) {
  return status === "APPROVED" ? "success" : status === "REJECTED" ? "error" : "warning";
}

function executionStatusLabel(status?: string) {
  const labels: Record<string, string> = {
    PENDING: "等待执行",
    RUNNING: "执行中",
    RETRY_WAIT: "等待重试",
    SUCCEEDED: "执行成功",
    DEAD_LETTER: "死信",
  };
  return status ? labels[status] || status : "未执行";
}

function actionLabel(action?: string) {
  const labels: Record<string, string> = {
    get_order_detail: "订单查询",
    get_shipment_trace: "物流查询",
    search_after_sales_policy: "政策检索",
    calculate_compensation: "规则计算",
    create_action_proposal: "生成方案",
  };
  return action ? labels[action] || action : "状态事件";
}

function formatMoney(amount: number, currency: string) {
  return `${currency} ${Number(amount || 0).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error || "未知错误");
}


