import { Card, Table, Typography } from "antd";

export function EvaluationsPage() {
  return <div className="page-stack"><div><Typography.Title level={3}>Evaluations</Typography.Title><Typography.Text type="secondary">评测数据集、Agent 版本和失败案例对比。</Typography.Text></div><Card><Table rowKey="id" dataSource={[]} columns={[{ title: "评测任务", dataIndex: "id" }, { title: "数据集", dataIndex: "datasetId" }, { title: "Agent 版本", dataIndex: "agentVersion" }, { title: "成功率", dataIndex: "successRate" }, { title: "平均延迟", dataIndex: "avgLatencyMs" }, { title: "Token", dataIndex: "totalTokens" }]} /></Card></div>;
}
