ALTER TABLE "agent_runs"
  ALTER COLUMN "input_tokens" DROP DEFAULT,
  ALTER COLUMN "input_tokens" DROP NOT NULL,
  ALTER COLUMN "output_tokens" DROP DEFAULT,
  ALTER COLUMN "output_tokens" DROP NOT NULL,
  ALTER COLUMN "total_tokens" DROP DEFAULT,
  ALTER COLUMN "total_tokens" DROP NOT NULL;
