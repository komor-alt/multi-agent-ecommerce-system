ALTER TABLE "recommendation_tasks"
  ADD COLUMN IF NOT EXISTS "completed_at" TIMESTAMP(3);
