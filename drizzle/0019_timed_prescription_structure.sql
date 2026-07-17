UPDATE "workout" AS "w"
SET "interval_structure" = jsonb_build_object(
  'warmupSeconds', 0,
  'cooldownSeconds', GREATEST(
    0,
    "w"."target_duration_seconds" - COALESCE((
      SELECT sum(
        ("block" ->> 'repetitions')::integer * COALESCE((
          SELECT sum(("segment" ->> 'durationSeconds')::integer)
          FROM jsonb_array_elements("block" -> 'segments') AS "segment"
        ), 0)
      )
      FROM jsonb_array_elements("w"."interval_structure") AS "block"
    ), 0)
  ),
  'blocks', "w"."interval_structure"
)
WHERE "w"."prescription_kind" = 'timed'
  AND jsonb_typeof("w"."interval_structure") = 'array';
--> statement-breakpoint
UPDATE "plan_adjustment"
SET "previous_state" = jsonb_set(
  "previous_state",
  '{intervalStructure}',
  jsonb_build_object(
    'warmupSeconds', 0,
    'cooldownSeconds', 0,
    'blocks', "previous_state" -> 'intervalStructure'
  )
)
WHERE jsonb_typeof("previous_state" -> 'intervalStructure') = 'array';
--> statement-breakpoint
UPDATE "plan_adjustment"
SET "new_state" = jsonb_set(
  "new_state",
  '{intervalStructure}',
  jsonb_build_object(
    'warmupSeconds', 0,
    'cooldownSeconds', 0,
    'blocks', "new_state" -> 'intervalStructure'
  )
)
WHERE jsonb_typeof("new_state" -> 'intervalStructure') = 'array';
