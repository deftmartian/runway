CREATE TABLE "mobile_request_receipt" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"request_id" uuid NOT NULL,
	"action" text NOT NULL,
	"payload_hash" text NOT NULL,
	"state" text DEFAULT 'processing' NOT NULL,
	"response_status" integer,
	"response_body" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone,
	CONSTRAINT "mobile_request_receipt_action_nonempty" CHECK (length(trim("mobile_request_receipt"."action")) between 1 and 80),
	CONSTRAINT "mobile_request_receipt_state_known" CHECK ("mobile_request_receipt"."state" in ('processing', 'completed')),
	CONSTRAINT "mobile_request_receipt_response_status_valid" CHECK ("mobile_request_receipt"."response_status" is null or "mobile_request_receipt"."response_status" between 200 and 499),
	CONSTRAINT "mobile_request_receipt_completion_consistent" CHECK ((
				("mobile_request_receipt"."state" = 'processing' and "mobile_request_receipt"."response_status" is null and "mobile_request_receipt"."response_body" is null and "mobile_request_receipt"."completed_at" is null)
				or
				("mobile_request_receipt"."state" = 'completed' and "mobile_request_receipt"."response_status" is not null and "mobile_request_receipt"."response_body" is not null and "mobile_request_receipt"."completed_at" is not null)
			))
);
--> statement-breakpoint
CREATE TABLE "device_code" (
	"id" text PRIMARY KEY NOT NULL,
	"device_code" text NOT NULL,
	"user_code" text NOT NULL,
	"user_id" text,
	"expires_at" timestamp NOT NULL,
	"status" text NOT NULL,
	"last_polled_at" timestamp,
	"polling_interval" integer,
	"client_id" text,
	"scope" text
);
--> statement-breakpoint
ALTER TABLE "athlete_profile" DROP CONSTRAINT "athlete_profile_browser_folder_generation_nonnegative";--> statement-breakpoint
ALTER TABLE "mobile_request_receipt" ADD CONSTRAINT "mobile_request_receipt_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "device_code" ADD CONSTRAINT "device_code_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "mobile_request_receipt_user_request_unique" ON "mobile_request_receipt" USING btree ("user_id","request_id");--> statement-breakpoint
CREATE INDEX "mobile_request_receipt_user_created_idx" ON "mobile_request_receipt" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "mobile_request_receipt_updated_idx" ON "mobile_request_receipt" USING btree ("updated_at");--> statement-breakpoint
CREATE UNIQUE INDEX "device_code_device_code_unique" ON "device_code" USING btree ("device_code");--> statement-breakpoint
CREATE UNIQUE INDEX "device_code_user_code_unique" ON "device_code" USING btree ("user_code");--> statement-breakpoint
CREATE INDEX "device_code_user_id_idx" ON "device_code" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "device_code_expires_at_idx" ON "device_code" USING btree ("expires_at");--> statement-breakpoint
ALTER TABLE "athlete_profile" DROP COLUMN "browser_folder_generation";