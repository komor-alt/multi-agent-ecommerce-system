import { Card, Input, Select, Space, Table, Tag, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { listProductCategories, listProducts } from "../../api/products";
import type { ProductSummary } from "../../types/contracts";

export function ProductsPage() {
  const [keyword, setKeyword] = useState("");
  const [category, setCategory] = useState<string | undefined>();
  const [stockState, setStockState] = useState<"in_stock" | "low_stock" | "out_of_stock" | undefined>();

  const productsQuery = useQuery({
    queryKey: ["products", keyword, category, stockState],
    queryFn: () => listProducts({ keyword, category, stockState, pageSize: 50 }),
  });
  const categoriesQuery = useQuery({ queryKey: ["product-categories"], queryFn: listProductCategories });

  const categoryOptions = useMemo(
    () => (categoriesQuery.data?.items || []).map((item) => ({ value: item.category, label: `${item.category} (${item.count})` })),
    [categoriesQuery.data?.items],
  );

  return (
    <div className="page-stack">
      <div>
        <Typography.Title level={3}>Products</Typography.Title>
        <Typography.Text type="secondary">来自 PostgreSQL 的商品数据、库存状态和知识库状态。</Typography.Text>
      </div>
      <Card>
        <Space className="table-toolbar" wrap>
          <Input.Search placeholder="搜索商品名称 / SKU / Product ID" style={{ width: 300 }} allowClear onSearch={setKeyword} />
          <Select placeholder="类目" style={{ width: 180 }} allowClear options={categoryOptions} value={category} onChange={setCategory} loading={categoriesQuery.isLoading} />
          <Select placeholder="库存" style={{ width: 160 }} allowClear value={stockState} onChange={setStockState} options={[{ value: "in_stock", label: "有库存" }, { value: "low_stock", label: "低库存" }, { value: "out_of_stock", label: "无库存" }]} />
        </Space>
        <Table<ProductSummary>
          rowKey="productId"
          loading={productsQuery.isLoading}
          dataSource={productsQuery.data?.items || []}
          pagination={{ pageSize: 10, total: productsQuery.data?.total || 0 }}
          columns={[
            { title: "Product ID", dataIndex: "productId", render: (id: string) => <Link to={`/products/${id}`}>{id}</Link> },
            { title: "名称", dataIndex: "name" },
            { title: "类目", dataIndex: "category" },
            { title: "品牌", dataIndex: "brand", render: (value?: string) => value || "-" },
            { title: "价格", dataIndex: "price", render: (value: number, row) => `${row.currency} ${value.toLocaleString()}` },
            { title: "库存", dataIndex: "stock", render: (value: number) => <Tag color={value === 0 ? "error" : value <= 20 ? "warning" : "success"}>{value}</Tag> },
            { title: "知识库", dataIndex: "knowledgeStatus", render: (value: string) => <Tag color={value === "ready" ? "success" : value === "failed" ? "error" : "default"}>{value}</Tag> },
            { title: "Embedding", dataIndex: "embeddingStatus", render: (value: string) => <Tag color={value === "ready" ? "success" : value === "failed" ? "error" : "default"}>{value}</Tag> },
            { title: "状态", dataIndex: "status", render: (value: string) => <Tag color={value === "active" ? "success" : "default"}>{value}</Tag> },
          ]}
        />
      </Card>
    </div>
  );
}
