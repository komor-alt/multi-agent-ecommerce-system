import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { Alert, Button, Card, Form, Input, Spin, Typography } from "antd";
import { apiGet, apiPost, ApiClientError } from "../api/client";
import { queryClient } from "./queryClient";

type Principal = { sub: string; username: string; roles: string[]; demo: boolean };
const AuthContext = createContext<{ principal: Principal; logout: () => Promise<void> } | null>(null);

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("Authentication context missing");
  return value;
}

export function AuthBoundary({ children }: { children: ReactNode }) {
  const [principal, setPrincipal] = useState<Principal | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();

  const reload = useCallback(async () => {
    try {
      setPrincipal(await apiGet<Principal>("/auth/me"));
      setError(undefined);
    } catch (failure) {
      setPrincipal(null);
      if (!(failure instanceof ApiClientError && failure.status === 401)) setError("无法连接认证服务，请稍后重试。");
    } finally { setLoading(false); }
  }, []);

  useEffect(() => {
    void reload();
    const expire = () => { setPrincipal(null); queryClient.clear(); };
    window.addEventListener("auth-expired", expire);
    const checkOnFocus = () => { void reload(); };
    window.addEventListener("focus", checkOnFocus);
    return () => { window.removeEventListener("auth-expired", expire); window.removeEventListener("focus", checkOnFocus); };
  }, [reload]);

  async function login(values: { username: string; password: string }) {
    setSubmitting(true);
    setError(undefined);
    try {
      const current = await apiPost<Principal>("/auth/login", values);
      queryClient.clear();
      setPrincipal(current);
    } catch (failure) {
      setError(failure instanceof ApiClientError && failure.status === 429 ? "尝试次数过多，请稍后再试。" : "登录失败，请检查账号和密码。");
    } finally { setSubmitting(false); }
  }

  async function logout() {
    try { await apiPost("/auth/logout", {}); }
    finally { setPrincipal(null); queryClient.clear(); }
  }

  if (loading) return <div style={{ padding: 64, textAlign: "center" }}><Spin tip="正在验证登录状态" /></div>;
  if (!principal) return (
    <div style={{ minHeight: "100vh", display: "grid", placeItems: "center", background: "#f5f7fb" }}>
      <Card style={{ width: 380, maxWidth: "90vw" }}>
        <Typography.Title level={3}>登录运营工作台</Typography.Title>
        {error && <Alert type="error" message={error} style={{ marginBottom: 20 }} />}
        <Form layout="vertical" onFinish={login}>
          <Form.Item name="username" label="账号" rules={[{ required: true, message: "请输入账号" }]}>
            <Input autoComplete="username" maxLength={128} />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: "请输入密码" }]}>
            <Input.Password autoComplete="current-password" maxLength={72} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} block>登录</Button>
        </Form>
      </Card>
    </div>
  );
  return <AuthContext.Provider value={{ principal, logout }}>{children}</AuthContext.Provider>;
}
