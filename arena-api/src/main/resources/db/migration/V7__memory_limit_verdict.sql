-- Adds MLE to the verdicts a submission may carry.
--
-- The sandboxed judge can distinguish a process killed for exceeding its memory ceiling from one
-- that crashed, so it has a verdict the simulated judge never had a way to produce. The CHECK
-- constraint enumerates the allowed values, so a new verdict is a migration rather than a code
-- change alone - which is the point of enumerating them: the database refuses to hold a verdict
-- the schema has not been told about, instead of silently accepting a typo.

ALTER TABLE submissions
    DROP CONSTRAINT ck_submissions_verdict;

ALTER TABLE submissions
    ADD CONSTRAINT ck_submissions_verdict
        CHECK (verdict IS NULL OR verdict IN ('AC', 'WA', 'TLE', 'RTE', 'CE', 'MLE'));
