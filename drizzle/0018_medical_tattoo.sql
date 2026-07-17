ALTER TYPE "public"."plan_adjustment_trigger" ADD VALUE 'manual_edit';--> statement-breakpoint
ALTER TYPE "public"."plan_adjustment_trigger" ADD VALUE 'manual_add';--> statement-breakpoint
ALTER TYPE "public"."plan_adjustment_trigger" ADD VALUE 'manual_remove';--> statement-breakpoint
ALTER TYPE "public"."plan_adjustment_trigger" ADD VALUE 'rebalance';--> statement-breakpoint
ALTER TABLE "workout" ADD COLUMN "is_removed" boolean DEFAULT false NOT NULL;