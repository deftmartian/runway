DROP INDEX "import_source_item_source_hash_unique";--> statement-breakpoint
CREATE INDEX "import_source_item_source_hash_idx" ON "import_source_item" USING btree ("source_id","content_hash");
