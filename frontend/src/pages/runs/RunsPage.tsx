import { Alert, Card, Input, Select, Space, Table, Tag, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { listAgentRuns } from "../../api/runs";
import type { AgentRunSummary } from "../../types/contracts";

export function RunsPage() {
  const runsQuery = useQuery({ queryKey: ["agent-runs"], queryFn: listAgentRuns });

  return (
    <div className="page-stack">
      <div>
        <Typography.Title level={3}>Agent Runs</Typography.Title>
        <Typography.Text type="secondary">执行记录列表，来自 NestJS `/api/v1/agent-runs`。</Typography.Text>
      </div>
      {runsQuery.error ? <Alert type="error" showIcon message="Agent Runs 暂不可用" description={(runsQuery.error as Error).message} /> : null}
      <Card>
        <Space className="table-toolbar" wrap>
          <Input.Search placeholder="搜索 Run ID / 用户 / 模型" style={{ width: 280 }} />
          <Select placeholder="状态" style={{ width: 160 }} options={[{ value: "running", label: "Running" }, { value: "completed", label: "Completed" }, { value: "failed", label: "Failed" }]} />
          <Select placeholder="任务类型" style={{ width: 200 }} options={[{ value: "product_recommendation", label: "Product Recommendation" }]} />
        </Space>
        <Table<AgentRunSummary>
          rowKey="id"
          loading={runsQuery.isLoading}
          dataSource={runsQuery.data?.items || []}
          columns={[
            { title: "Run ID", dataIndex: "id", render: (id: string) => <Link to={`/runs/${id}`}>{id.slice(0, 8)}</Link> },
            { title: "任务类型", dataIndex: "taskType" },
            { title: "场景", dataIndex: "scene", render: (scene?: string) => scene || "-" },
            { title: "状态", dataIndex: "status", render: (status: string) => <Tag color={status === "completed" ? "success" : status === "failed" ? "error" : "processing"}>{status}</Tag> },
            { title: "模型", dataIndex: "modelName" },
            { title: "步骤", dataIndex: "stepCount" },
            { title: "工具调用", dataIndex: "toolCallCount" },
            { title: "延迟", dataIndex: "latencyMs", render: (value?: number) => value == null ? "-" : `${value} ms` },
            { title: "Token", dataIndex: "totalTokens" },
            { title: "创建时间", dataIndex: "createdAt" },
          ]}
        />
      </Card>
    </div>
  );
}
