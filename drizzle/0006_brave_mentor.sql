ALTER TABLE "activity_import" ADD CONSTRAINT "activity_import_result_known" CHECK ("activity_import"."result" in ('imported'));--> statement-breakpoint
ALTER TABLE "import_source" ADD CONSTRAINT "import_source_type_known" CHECK ("import_source"."type" in ('nextcloud_share'));--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_status_known" CHECK ("import_source_item"."status" in ('importing', 'imported', 'failed'));
