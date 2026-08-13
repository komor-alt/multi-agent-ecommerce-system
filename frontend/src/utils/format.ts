/**
 * 统一的耗时格式化：前端计时、事件 latencyMs、后端 durationMs 都走这里，
 * 避免把毫秒硬编码成固定展示结果。
 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || Number.isNaN(ms)) return "—";
  const value = Math.max(0, Math.round(ms));
  if (value < 1000) return `${value}ms`;
  const totalSeconds = Math.round(value / 1000);
  if (totalSeconds < 60) return `${(value / 1000).toFixed(1)}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}
