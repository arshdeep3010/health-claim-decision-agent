-- Keep the decision's one-line summary (the AI's reasoning, or the rule engine's) so
-- GET /claims/{id}/decision returns it, not only the /analyze response.
ALTER TABLE decision ADD COLUMN summary text;
