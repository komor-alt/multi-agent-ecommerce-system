import { Card, Form, InputNumber, Select, Switch, Typography } from "antd";

export function SettingsPage() {
  return (
    <div className="page-stack">
      <div><Typography.Title level={3}>Settings</Typography.Title><Typography.Text type="secondary">模型、工具白名单、RAG 和 Agent 执行预算配置。</Typography.Text></div>
      <Card>
        <Form layout="vertical">
          <Form.Item label="模型" extra="后端只保存模型名，不向前端返回 API Key。"><Select placeholder="选择模型" options={[{ value: "deepseek-v4-flash", label: "DeepSeek Flash" }]} /></Form.Item>
          <Form.Item label="Agent 最大执行步数" extra="限制工具循环，防止模型空转。"><InputNumber min={1} max={32} defaultValue={8} /></Form.Item>
          <Form.Item label="Temperature" extra="控制生成随机性，推荐任务默认保持较低。"><InputNumber min={0} max={2} step={0.1} defaultValue={0.3} /></Form.Item>
          <Form.Item label="RAG Top-K" extra="控制每轮检索注入的证据数量。"><InputNumber min={1} max={20} defaultValue={5} /></Form.Item>
          <Form.Item label="启用人工审核" extra="高风险动作进入人工审核链路。"><Switch defaultChecked /></Form.Item>
        </Form>
      </Card>
    </div>
  );
}
