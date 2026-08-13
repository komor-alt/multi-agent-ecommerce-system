/**
 * 售后运营中心 · 工单工作区（无状态展示组件）。
 *
 * 三栏 Context / Decision / Approval + Agent Execution Trace 折叠面板。
 * 全部数据（ticket / events / checks / risk）由父页面派生后传入，
 * 审批与重试动作仅通过回调上报，不持有任何 query/mutation/SSE 状态。
 */

import {
  AuditOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Empty,
  Input,
  Popconfirm,
  Skeleton,
  Space,
  Table,
  Tag,
  Timeline,
  Tooltip,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import type { ReactNode } from "react";
import type { AfterSalesEvent, AfterSalesIntake, AfterSalesTicket } from "../../types/afterSales";
import { formatDuration } from "../../utils/format";
import {
  countryLabel,
  deriveEvidencePlan,
  EVIDENCE_CATEGORIES,
  eventTypeLabel,
  evidenceCategoryLabel,
  evidenceTypeLabel,
  executionStatusLabel,
  formatDateTime,
  formatMoney,
  issueTypeLabel,
  plannerReasonLabel,
  plannerSourceLabel,
  policyVersionLabel,
  proposalStatusLabel,
  riskLevelColor,
  riskLevelLabel,
  ticketStatusColor,
  ticketStatusLabel,
} from "./afterSalesLogic";
import type {
  ApprovalChecks,
  EvidenceCategory,
  EvidenceItem,
  EvidencePlanState,
  RiskAssessment,
  RiskLevel,
} from "./afterSalesLogic";

export function AfterSalesWorkspace({
  ticket,
  loading,
  events,
  evidenceItems,
  checks,
  risk,
  operatorId,
  reviewComment,
  onOperatorIdChange,
  onReviewCommentChange,
  onApprove,
  onReject,
  reviewPending,
  onRetryExecution,
  traceDurationMs,
  liveRunActive,
}: {
  ticket?: AfterSalesTicket;
  loading: boolean;
  events: AfterSalesEvent[];
  evidenceItems: EvidenceItem[];
  checks: ApprovalChecks;
  risk: RiskAssessment;
  operatorId: string;
  reviewComment: string;
  onOperatorIdChange: (value: string) => void;
  onReviewCommentChange: (value: string) => void;
  onApprove: () => void;
  onReject: () => void;
  reviewPending: boolean;
  onRetryExecution: (jobId: string) => void;
  traceDurationMs: number | null;
  liveRunActive: boolean;
}) {
  return (
    <>
      <div className="case-workspace">
        <section className="workspace-panel">
          <ContextPanel ticket={ticket} loading={loading} />
        </section>
        <section className="workspace-panel">
          <DecisionCenterPanel ticket={ticket} loading={loading} events={events} evidenceItems={evidenceItems} />
        </section>
        <section className="workspace-panel">
          <ApprovalPanel
            ticket={ticket}
            loading={loading}
            checks={checks}
            risk={risk}
            operatorId={operatorId}
            reviewComment={reviewComment}
            onOperatorIdChange={onOperatorIdChange}
            onReviewCommentChange={onReviewCommentChange}
            onApprove={onApprove}
            onReject={onReject}
            reviewPending={reviewPending}
            onRetryExecution={onRetryExecution}
          />
        </section>
      </div>

      {/* Debug 轨迹默认折叠：决策中心才是页面焦点，轨迹只按需展开，且从不展示思维链。 */}
      <Collapse
        className="trace-collapse"
        defaultActiveKey={[]}
        items={[{
          key: "trace",
          label: (
            <Space>
              <AuditOutlined />
              <Typography.Text strong>Agent Execution Trace</Typography.Text>
              <Tag>{events.length} 条事件</Tag>
              {traceDurationMs != null ? <Tag>{formatDuration(traceDurationMs)}</Tag> : null}
              {liveRunActive ? <Tag color="processing">运行中</Tag> : null}
            </Space>
          ),
          children: <TraceTable events={events} running={liveRunActive} />,
        }]}
      />
    </>
  );
}

function ContextPanel({ ticket, loading }: { ticket?: AfterSalesTicket; loading: boolean }) {
  if (loading) return <Skeleton active paragraph={{ rows: 6 }} />;
  if (!ticket) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择工单" />;
  }
  const finalAnswer = ticket.run?.finalAnswer;
  return (
    <div className="panel-body">
      <PanelTitle>客户诉求</PanelTitle>
      <Typography.Paragraph className="customer-message">{ticket.customerMessage || "—"}</Typography.Paragraph>

      <PanelTitle>订单核心信息</PanelTitle>
      {finalAnswer?.order ? (
        <Descriptions
          column={1}
          size="small"
          items={[
            { key: "order", label: "订单号", children: <Typography.Text code>{finalAnswer.order.orderId}</Typography.Text> },
            { key: "country", label: "国家", children: `${countryLabel(finalAnswer.order.country)} (${finalAnswer.order.country})` },
            { key: "amount", label: "实付金额", children: formatMoney(finalAnswer.order.paidAmount, finalAnswer.order.currency) },
            { key: "status", label: "履约状态", children: finalAnswer.order.fulfillmentStatus },
            { key: "route", label: "履约路线", children: `${finalAnswer.order.warehouseRegion} → ${finalAnswer.order.country}` },
            { key: "tracking", label: "运单号", children: <Typography.Text code>{finalAnswer.order.trackingNumber}</Typography.Text> },
          ]}
        />
      ) : (
        <PanelEmpty text="订单信息待 Agent 核验" />
      )}

      <PanelTitle>物流轨迹</PanelTitle>
      {finalAnswer?.shipment ? (
        <>
          <Typography.Text type="secondary" className="shipment-summary">
            未更新 {finalAnswer.shipment.inactiveDays} 天 · 延迟 {finalAnswer.shipment.delayDays} 天 · 状态{" "}
            {finalAnswer.shipment.status}
          </Typography.Text>
          <Timeline
            className="shipment-timeline"
            items={finalAnswer.shipment.timeline.map((item) => ({
              color: item.status === "CUSTOMS_DOCUMENT_REQUIRED" ? "orange" : "green",
              children: (
                <div className="event-line">
                  <Space size={6}>
                    <Typography.Text strong>{item.location}</Typography.Text>
                    <Typography.Text type="secondary">{formatDateTime(item.occurredAt)}</Typography.Text>
                  </Space>
                  <div className="event-line-summary">{item.description}</div>
                </div>
              ),
            }))}
          />
        </>
      ) : (
        <PanelEmpty text="物流轨迹待 Agent 核验" />
      )}

      <PanelTitle>用户 / 工单基础信息</PanelTitle>
      <Descriptions
        column={1}
        size="small"
        items={[
          { key: "ticketNo", label: "工单号", children: <Typography.Text code>{ticket.ticketNo}</Typography.Text> },
          { key: "status", label: "状态", children: <Tag color={ticketStatusColor(ticket.status)}>{ticketStatusLabel(ticket.status)}</Tag> },
          { key: "issue", label: "问题类型", children: issueTypeLabel(ticket.issueType) },
          { key: "user", label: "用户 ID", children: ticket.userId || "未知" },
          { key: "created", label: "创建时间", children: formatDateTime(ticket.createdAt) },
          { key: "updated", label: "更新时间", children: formatDateTime(ticket.updatedAt) },
        ]}
      />
    </div>
  );
}

/**
 * 紧凑的「Intake 分析」：建议之前展示结构化分类。规则降级时只显示克制的 Tag，不展示技术堆栈。
 */
function IntakeAnalysisPanel({ intake }: { intake: AfterSalesIntake }) {
  const fallback = intake.source === "RULE_FALLBACK";
  return (
    <div className="intake-panel">
      <div className="intake-head">
        <Typography.Text strong>Intake 分析</Typography.Text>
        {fallback ? (
          <Tag className="intake-source-tag">规则降级</Tag>
        ) : (
          <Tag color="green" className="intake-source-tag">LLM</Tag>
        )}
      </div>
      <Space size={4} wrap>
        <Tag>{issueTypeLabel(intake.issueType)}</Tag>
        {intake.intents.map((intent) => <Tag key={intent} color="blue">{intentLabel(intent)}</Tag>)}
        <Tag color={riskLevelColor(intake.urgency)}>{riskLevelLabel(intake.urgency)}紧急度</Tag>
        {intake.entities?.deadline ? <Tag>预计 {intake.entities.deadline}</Tag> : null}
      </Space>
    </div>
  );
}

/** 意图展示名（与后端 INTENT 白名单一致）。 */
function intentLabel(intent: string): string {
  return ({ TRACK_SHIPMENT: "查询物流", REQUEST_REFUND: "申请退款" } as Record<string, string>)[intent] || intent;
}

/**
 * 紧凑 Evidence Plan：Planner 驱动的取证进度。ORDER/SHIPMENT/POLICY 三份证据的
 * 请求与完成状态 + Ready for Decision。只依赖 Planner 事件与证据 ID，不展示任何模型输出。
 */
function EvidencePlanPanel({ plan }: { plan: EvidencePlanState }) {
  return (
    <div className="evidence-plan">
      <div className="evidence-plan-head">
        <Typography.Text strong>Evidence Plan</Typography.Text>
        {plan.readyForDecision ? (
          <Tag color="green">Ready for Decision</Tag>
        ) : (
          <Tag color="processing">取证中</Tag>
        )}
      </div>
      <div className="evidence-plan-row">
        {plan.items.map((item) => (
          <Tooltip
            key={item.type}
            title={
              item.verified
                ? `${evidenceTypeLabel(item.type)}证据已核验`
                : item.requested
                  ? `${evidenceTypeLabel(item.type)}证据已请求，待核验`
                  : `${evidenceTypeLabel(item.type)}证据未请求`
            }
          >
            <Tag
              icon={item.verified ? <CheckCircleOutlined /> : item.requested ? <ClockCircleOutlined /> : <CloseCircleOutlined />}
              color={item.verified ? "success" : item.requested ? "processing" : "default"}
              className="evidence-plan-tag"
            >
              {evidenceTypeLabel(item.type)}
            </Tag>
          </Tooltip>
        ))}
      </div>
    </div>
  );
}

function DecisionCenterPanel({
  ticket,
  loading,
  events,
  evidenceItems,
}: {
  ticket?: AfterSalesTicket;
  loading: boolean;
  events: AfterSalesEvent[];
  evidenceItems: EvidenceItem[];
}) {
  if (loading) return <Skeleton active paragraph={{ rows: 6 }} />;
  const finalAnswer = ticket?.run?.finalAnswer;
  const proposal = ticket?.proposal;
  const policy = finalAnswer?.policy;
  const evidencePlan = deriveEvidencePlan(events);
  if (!finalAnswer && !proposal) {
    if (ticket?.status === "ANALYZING" && events.length > 0) {
      // 直播分析中：先展示实时的 Evidence Plan，再提示建议尚未生成。
      return (
        <div className="panel-body">
          <EvidencePlanPanel plan={evidencePlan} />
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Agent 正在分析…" />
        </div>
      );
    }
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Agent 完成分析后展示建议与证据" />;
  }
  const evidenceRows = evidenceItems.map((item) => ({
    key: item.category,
    category: item.category,
    evidenceId: item.evidenceId || "—",
    verified: item.verified,
    policyInfo:
      item.category === "POLICY" && policy
        ? `${policyVersionLabel(policy.version)} · 条款 ${policy.section}`
        : undefined,
  }));
  return (
    <div className="panel-body">
      {finalAnswer?.intake ? <IntakeAnalysisPanel intake={finalAnswer.intake} /> : null}
      <EvidencePlanPanel plan={evidencePlan} />
      <div className="suggestion-head">
        <div>
          <Typography.Text type="secondary">AI 建议金额</Typography.Text>
          <div className="suggestion-amount">{proposal ? formatMoney(proposal.amount, proposal.currency) : "—"}</div>
        </div>
        {proposal ? <Tag color="blue">{proposal.actionType}</Tag> : null}
      </div>
      <PanelTitle>判断理由</PanelTitle>
      <Typography.Paragraph className="decision-summary">
        {proposal?.decisionSummary || finalAnswer?.decisionSummary || "—"}
      </Typography.Paragraph>
      {finalAnswer?.compensation ? (
        <>
          <PanelTitle>Agent 结论</PanelTitle>
          <Descriptions
            column={1}
            size="small"
            items={[
              {
                key: "eligible",
                label: "是否可补偿",
                children: finalAnswer.compensation.eligible ? "可补偿" : "不可补偿",
              },
              {
                key: "amount",
                label: "计算金额",
                children: formatMoney(finalAnswer.compensation.amount, finalAnswer.compensation.currency),
              },
              { key: "reason", label: "依据", children: finalAnswer.compensation.reason },
            ]}
          />
        </>
      ) : null}
      <PanelTitle>Evidence 清单</PanelTitle>
      <Table
        rowKey="key"
        size="small"
        className="evidence-table"
        dataSource={evidenceRows}
        pagination={false}
        locale={{ emptyText: <PanelEmpty text="暂无证据" /> }}
        columns={[
          {
            title: "类别",
            dataIndex: "category",
            width: 80,
            render: (category: EvidenceCategory) => evidenceCategoryLabel(category),
          },
          {
            title: "证据 ID",
            dataIndex: "evidenceId",
            render: (value: string) => <Typography.Text code className="evidence-id">{value}</Typography.Text>,
          },
          {
            title: "验证状态",
            dataIndex: "verified",
            width: 100,
            render: (verified: boolean) => (
              verified
                ? <Typography.Text type="success"><CheckCircleOutlined /> 已验证</Typography.Text>
                : <Typography.Text type="secondary"><CloseCircleOutlined /> 未验证</Typography.Text>
            ),
          },
          {
            title: "政策版本 / 条款",
            dataIndex: "policyInfo",
            width: 150,
            render: (value?: string) => value ? <Typography.Text type="secondary">{value}</Typography.Text> : "—",
          },
        ]}
      />
    </div>
  );
}

function ApprovalPanel({
  ticket,
  loading,
  checks,
  risk,
  operatorId,
  reviewComment,
  onOperatorIdChange,
  onReviewCommentChange,
  onApprove,
  onReject,
  reviewPending,
  onRetryExecution,
}: {
  ticket?: AfterSalesTicket;
  loading: boolean;
  checks: ApprovalChecks;
  risk: RiskAssessment;
  operatorId: string;
  reviewComment: string;
  onOperatorIdChange: (value: string) => void;
  onReviewCommentChange: (value: string) => void;
  onApprove: () => void;
  onReject: () => void;
  reviewPending: boolean;
  onRetryExecution: (jobId: string) => void;
}) {
  if (loading) return <Skeleton active paragraph={{ rows: 6 }} />;
  const proposal = ticket?.proposal;
  const finalAnswer = ticket?.run?.finalAnswer;
  if (!proposal) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={ticket?.status === "ANALYZING" ? "Agent 分析中，尚未生成方案" : "暂无待审批方案"} />;
  }
  const isPending = proposal.status === "PENDING";
  return (
    <div className="panel-body">
      <div className="approval-head">
        <Space>
          <Typography.Text strong>人工审批</Typography.Text>
          <Tag color={proposal.status === "APPROVED" ? "success" : proposal.status === "REJECTED" ? "error" : "warning"}>
            {proposalStatusLabel(proposal.status)}
          </Tag>
        </Space>
        <Tooltip title={risk.rule}>
          <Tag color={riskLevelColor(risk.level)}>
            {riskLevelLabel(risk.level)}风险 · 规则评估
          </Tag>
        </Tooltip>
      </div>

      <Descriptions
        column={1}
        size="small"
        items={[
          {
            key: "amount",
            label: "补偿金额",
            children: <Typography.Text strong>{formatMoney(proposal.amount, proposal.currency)}</Typography.Text>,
          },
          {
            key: "action",
            label: "动作类型",
            children: proposal.actionType,
          },
          {
            key: "policyVersion",
            label: "政策版本",
            children: policyVersionLabel(proposal.policyVersion),
          },
        ]}
      />

      <PanelTitle>只读检查项</PanelTitle>
      <CheckRow
        label="证据完整"
        state={checks.evidenceComplete}
        detail={
          checks.evidenceComplete === undefined
            ? "数据不足，Agent 分析完成后评估"
            : `${EVIDENCE_CATEGORIES.length} 类证据（订单 / 物流 / 政策 / 计算）${checks.evidenceComplete ? "全部产生" : "存在缺失"}`
        }
      />
      <CheckRow
        label="符合政策"
        state={checks.policyCompliant}
        detail={
          checks.policyCompliant === undefined
            ? "数据不足，Agent 分析完成后评估"
            : checks.policyCompliant
              ? `规则判定可补偿：${finalAnswer?.compensation?.reason || "符合政策阈值"}`
              : "规则判定不可补偿"
        }
      />
      <CheckRow
        label="金额未超限"
        state={checks.amountWithinLimit}
        detail={
          checks.amountWithinLimit === undefined
            ? "数据不足，Agent 分析完成后评估"
            : `补偿 ${formatMoney(proposal.amount, proposal.currency)} ≤ 政策上限 ${formatMoney(finalAnswer?.policy?.maximumCompensation, proposal.currency)}`
        }
      />

      <PanelTitle>审批信息</PanelTitle>
      <Space direction="vertical" size={8} style={{ width: "100%" }}>
        <Input
          value={operatorId}
          onChange={(event) => onOperatorIdChange(event.target.value)}
          addonBefore="审批人"
          disabled={!isPending}
        />
        <Input.TextArea
          value={reviewComment}
          onChange={(event) => onReviewCommentChange(event.target.value)}
          rows={3}
          disabled={!isPending}
        />
        {isPending ? (
          <Space>
            <Popconfirm
              title="确认批准该补偿方案？"
              description="批准后将创建唯一执行任务并发放补偿券。"
              onConfirm={onApprove}
            >
              <Button type="primary" icon={<CheckCircleOutlined />} loading={reviewPending}>批准并执行</Button>
            </Popconfirm>
            <Popconfirm title="确认驳回该方案？" onConfirm={onReject}>
              <Button danger icon={<CloseCircleOutlined />}>驳回</Button>
            </Popconfirm>
          </Space>
        ) : (
          <Descriptions
            column={1}
            size="small"
            items={[
              { key: "reviewedBy", label: "审批人", children: proposal.reviewedBy || "—" },
              { key: "comment", label: "审批意见", children: proposal.reviewComment || "—" },
              { key: "time", label: "审批时间", children: formatDateTime(proposal.reviewedAt) },
            ]}
          />
        )}
        {ticket?.executionJob ? (
          <ExecutionStatusBlock ticket={ticket} onRetry={() => onRetryExecution(ticket.executionJob!.id)} />
        ) : null}
      </Space>
    </div>
  );
}

function CheckRow({ label, state, detail }: { label: string; state: boolean | undefined; detail: string }) {
  const icon =
    state === undefined
      ? <QuestionCircleOutlined className="check-icon check-icon-unknown" />
      : state
        ? <CheckCircleOutlined className="check-icon check-icon-pass" />
        : <CloseCircleOutlined className="check-icon check-icon-fail" />;
  const stateText = state === undefined ? "待分析" : state ? "通过" : "未通过";
  return (
    <div className="check-row">
      <Space size={6}>
        {icon}
        <Typography.Text strong>{label}</Typography.Text>
        <Tag color={state === undefined ? "default" : state ? "success" : "error"}>{stateText}</Tag>
      </Space>
      <Typography.Text type="secondary" className="check-detail">{detail}</Typography.Text>
    </div>
  );
}

function ExecutionStatusBlock({ ticket, onRetry }: { ticket: AfterSalesTicket; onRetry: () => void }) {
  const execution = ticket.executionJob;
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
          <Typography.Text type="secondary">尝试次数：{execution.attemptCount}</Typography.Text>
          {execution.result?.externalReference ? <Typography.Text code>{execution.result.externalReference}</Typography.Text> : null}
          {execution.lastError ? <Typography.Text type="secondary">{execution.lastError}</Typography.Text> : null}
          {retryable ? <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重新执行</Button> : null}
        </Space>
      )}
    />
  );
}

function TraceTable({ events, running }: { events: AfterSalesEvent[]; running: boolean }) {
  if (!events.length) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={running ? "正在建立实时事件流" : "暂无运行事件"} />;
  }
  return (
    <Table<AfterSalesEvent>
      rowKey="eventId"
      size="small"
      className="trace-table"
      dataSource={events}
      pagination={false}
      scroll={{ x: 760 }}
      footer={running ? () => <Typography.Text type="secondary"><SyncOutlined spin /> Agent 正在执行下一步</Typography.Text> : undefined}
      columns={[
        {
          title: "#",
          dataIndex: "sequence",
          width: 56,
          render: (value: number) => <Typography.Text type="secondary">{value}</Typography.Text>,
        },
        {
          title: "事件",
          key: "name",
          width: 190,
          render: (_, event) => (
            <div className="queue-cell">
              <Space size={6}>
                <Typography.Text strong>{event.name}</Typography.Text>
                {event.type.startsWith("planning_") ? <Tag className="planner-kind-tag">Planner</Tag> : null}
              </Space>
              <Typography.Text type="secondary" className="queue-cell-sub">{eventTypeLabel(event.type)}</Typography.Text>
            </div>
          ),
        },
        {
          title: "摘要",
          key: "summary",
          render: (_, event) => (
            <div className="trace-summary">
              {event.type.startsWith("planning_") ? (
                <PlannerTraceSummary event={event} />
              ) : (
                <>
                  {event.data.summary ? (
                    <Tooltip title={event.data.summary}>
                      <Typography.Text type="secondary" className="trace-summary-text">{event.data.summary}</Typography.Text>
                    </Tooltip>
                  ) : <Typography.Text type="secondary">—</Typography.Text>}
                  {event.data.evidenceIds?.length ? (
                    <Space wrap size={4} className="evidence-list">
                      {event.data.evidenceIds.map((id) => (
                        <Typography.Text code key={id} className="evidence-id">{id}</Typography.Text>
                      ))}
                    </Space>
                  ) : null}
                </>
              )}
            </div>
          ),
        },
        {
          title: "耗时",
          key: "duration",
          width: 110,
          render: (_, event) => {
            const value =
              event.type === "tool_completed" || event.type === "retrieval_completed"
                || event.type === "intake_completed" || event.type === "planning_completed"
                ? event.data.latencyMs
                : event.type === "run_completed" || event.type === "error"
                  ? event.data.durationMs
                  : null;
            return <Typography.Text type="secondary" className="tabular">{formatDuration(value)}</Typography.Text>;
          },
        },
        {
          title: "状态",
          dataIndex: "status",
          width: 90,
          render: (value: string) => (
            <Tag color={value === "running" ? "processing" : value === "failed" ? "error" : "success"}>
              {value === "running" ? "进行中" : value === "failed" ? "失败" : "成功"}
            </Tag>
          ),
        },
      ]}
    />
  );
}

/**
 * Planner 事件的结构化摘要：证据 + 本地化理由 + 来源。只展示后端输出的安全字段，
 * 从不展示思维链或模型原始输出。
 */
function PlannerTraceSummary({ event }: { event: AfterSalesEvent }) {
  const evidence = typeof event.data.nextEvidence === "string" ? event.data.nextEvidence : undefined;
  const reasonCode = typeof event.data.reasonCode === "string" ? event.data.reasonCode : undefined;
  const fallbackReason = typeof event.data.fallbackReason === "string" ? event.data.fallbackReason : undefined;
  const source = typeof event.data.source === "string" ? event.data.source : undefined;
  return (
    <Space wrap size={4}>
      {evidence ? <Tag color="blue">{evidenceTypeLabel(evidence)}</Tag> : null}
      {reasonCode ? <Typography.Text type="secondary">{plannerReasonLabel(reasonCode)}</Typography.Text> : null}
      {fallbackReason ? <Tag color="orange">{plannerReasonLabel(fallbackReason)}</Tag> : null}
      <Tag className="planner-source-tag">{plannerSourceLabel(source)}</Tag>
    </Space>
  );
}

function PanelTitle({ children }: { children: ReactNode }) {
  return <div className="panel-title"><Typography.Text strong>{children}</Typography.Text></div>;
}

function PanelEmpty({ text }: { text: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />;
}
