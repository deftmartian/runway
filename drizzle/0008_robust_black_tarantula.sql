UPDATE "activity"
SET "workout_id" = NULL
WHERE "id" IN (
	SELECT "id"
	FROM (
		SELECT
			"id",
			row_number() OVER (PARTITION BY "workout_id" ORDER BY "created_at" DESC, "id" DESC) AS "row_number"
		FROM "activity"
		WHERE "workout_id" IS NOT NULL
	) AS "ranked_activity"
	WHERE "row_number" > 1
);
--> statement-breakpoint
CREATE UNIQUE INDEX "activity_workout_unique" ON "activity" USING btree ("workout_id") WHERE "activity"."workout_id" is not null;
