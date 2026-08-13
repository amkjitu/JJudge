-- Records how a verdict was reached, not just what it was.
--
-- The judge produces verdicts two ways: by compiling and running the submission against real test
-- cases, or by hashing it when the problem has no test cases to run against. Stored as they were,
-- the two are indistinguishable - a simulated WA and an earned one are the same four characters in
-- the same column - and anyone reading a submission would assume the stronger of the two.
--
-- Nullable, and deliberately so. Every row already in this table predates the distinction: the
-- seeded history was never judged at all, and submissions judged before this column existed were
-- not recorded either way. Backfilling them with a value would be inventing history. NULL means
-- "not recorded", which is the truth, and the UI says nothing rather than making a claim.

ALTER TABLE submissions
    ADD COLUMN judged_by VARCHAR(20);

ALTER TABLE submissions
    ADD CONSTRAINT ck_submissions_judged_by
        CHECK (judged_by IS NULL OR judged_by IN ('EXECUTED', 'SIMULATED'));

COMMENT ON COLUMN submissions.judged_by IS
    'How the verdict was reached: EXECUTED (run in a sandbox against real test cases) or '
        'SIMULATED (derived from a hash). NULL for rows that predate the column.';
