DELETE FROM "workout_feedback"
WHERE "id" IN (
	SELECT "id"
	FROM (
		SELECT
			"id",
			row_number() OVER (PARTITION BY "workout_id" ORDER BY "created_at" DESC, "id" DESC) AS "row_number"
		FROM "workout_feedback"
	) AS "ranked_feedback"
	WHERE "row_number" > 1
);--> statement-breakpoint
DROP INDEX IF EXISTS "workout_feedback_workout_idx";--> statement-breakpoint
ALTER TABLE "activity" ALTER COLUMN "route_summary" DROP DEFAULT;--> statement-breakpoint
CREATE UNIQUE INDEX IF NOT EXISTS "workout_feedback_workout_unique" ON "workout_feedback" USING btree ("workout_id");
