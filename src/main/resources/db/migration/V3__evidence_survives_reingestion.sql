-- Re-ingesting a policy document (e.g. after switching embedding models) replaces
-- its chunks. decision_evidence is a snapshot (snippet, section, page, similarity
-- are copied at decision time), so past decisions must keep their evidence when
-- the chunk row it pointed at is replaced: clear the link instead of blocking the
-- delete.
ALTER TABLE decision_evidence DROP CONSTRAINT decision_evidence_policy_chunk_id_fkey;
ALTER TABLE decision_evidence
    ADD CONSTRAINT decision_evidence_policy_chunk_id_fkey
        FOREIGN KEY (policy_chunk_id) REFERENCES policy_chunk(id) ON DELETE SET NULL;
