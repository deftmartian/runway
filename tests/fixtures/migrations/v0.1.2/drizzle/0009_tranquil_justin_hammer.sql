CREATE TABLE "password_reset_rate_limit" (
	"key_hash" text PRIMARY KEY NOT NULL,
	"count" integer DEFAULT 0 NOT NULL,
	"reset_at" timestamp NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "password_reset_rate_limit_count_nonnegative" CHECK ("password_reset_rate_limit"."count" >= 0)
);
--> statement-breakpoint
CREATE INDEX "password_reset_rate_limit_reset_idx" ON "password_reset_rate_limit" USING btree ("reset_at");
