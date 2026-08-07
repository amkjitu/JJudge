-- Submission history for the three demo users, shaped to give each a distinct profile:
--   alice (1150) - solid on arrays/strings/hashing, bounces off dp and graphs
--   bob   (1450) - broad coverage, repeatedly fails dp and shortest-path
--   carol (1750) - strong nearly everywhere, weak on geometry
--
-- Ids are left to the identity column. user_tag_stats is *derived* from these rows at the
-- bottom of this migration rather than hand-written, so the counters can never drift out of
-- sync with the submissions that justify them.

-- ---------------------------------------------------------------------------------------
-- alice
-- ---------------------------------------------------------------------------------------
INSERT INTO submissions (user_id, problem_id, language, status, verdict, runtime_ms, submitted_at)
VALUES (2, 1, 'PYTHON', 'DONE', 'WA', 41, now() - INTERVAL '46 days'),
       (2, 1, 'PYTHON', 'DONE', 'AC', 38, now() - INTERVAL '45 days'),
       (2, 2, 'PYTHON', 'DONE', 'AC', 52, now() - INTERVAL '44 days'),
       (2, 3, 'PYTHON', 'DONE', 'AC', 33, now() - INTERVAL '43 days'),
       (2, 4, 'PYTHON', 'DONE', 'AC', 21, now() - INTERVAL '42 days'),
       (2, 5, 'PYTHON', 'DONE', 'AC', 47, now() - INTERVAL '40 days'),
       (2, 6, 'PYTHON', 'DONE', 'AC', 60, now() - INTERVAL '38 days'),
       (2, 8, 'PYTHON', 'DONE', 'AC', 29, now() - INTERVAL '35 days'),
       (2, 9, 'PYTHON', 'DONE', 'WA', 88, now() - INTERVAL '34 days'),
       (2, 9, 'PYTHON', 'DONE', 'AC', 91, now() - INTERVAL '33 days'),
       (2, 11, 'PYTHON', 'DONE', 'TLE', 1000, now() - INTERVAL '31 days'),
       (2, 11, 'PYTHON', 'DONE', 'AC', 120, now() - INTERVAL '30 days'),
       (2, 12, 'PYTHON', 'DONE', 'AC', 44, now() - INTERVAL '28 days'),
       -- unsolved: dp and graphs
       (2, 10, 'PYTHON', 'DONE', 'WA', 66, now() - INTERVAL '20 days'),
       (2, 10, 'PYTHON', 'DONE', 'TLE', 1000, now() - INTERVAL '19 days'),
       (2, 14, 'PYTHON', 'DONE', 'RTE', 12, now() - INTERVAL '15 days'),
       (2, 16, 'PYTHON', 'DONE', 'WA', 140, now() - INTERVAL '12 days'),
       (2, 16, 'PYTHON', 'DONE', 'WA', 137, now() - INTERVAL '11 days'),
       -- still in the pipeline, exercises the QUEUED state
       (2, 22, 'PYTHON', 'QUEUED', NULL, NULL, now() - INTERVAL '2 minutes');

-- ---------------------------------------------------------------------------------------
-- bob
-- ---------------------------------------------------------------------------------------
INSERT INTO submissions (user_id, problem_id, language, status, verdict, runtime_ms, submitted_at)
VALUES (3, 1, 'JAVA', 'DONE', 'AC', 96, now() - INTERVAL '210 days'),
       (3, 3, 'JAVA', 'DONE', 'AC', 88, now() - INTERVAL '205 days'),
       (3, 5, 'JAVA', 'DONE', 'AC', 101, now() - INTERVAL '200 days'),
       (3, 7, 'JAVA', 'DONE', 'AC', 154, now() - INTERVAL '190 days'),
       (3, 8, 'JAVA', 'DONE', 'AC', 77, now() - INTERVAL '185 days'),
       (3, 9, 'JAVA', 'DONE', 'AC', 133, now() - INTERVAL '180 days'),
       (3, 10, 'JAVA', 'DONE', 'AC', 91, now() - INTERVAL '170 days'),
       (3, 12, 'JAVA', 'DONE', 'AC', 84, now() - INTERVAL '165 days'),
       (3, 13, 'JAVA', 'DONE', 'WA', 210, now() - INTERVAL '150 days'),
       (3, 13, 'JAVA', 'DONE', 'AC', 198, now() - INTERVAL '149 days'),
       (3, 14, 'JAVA', 'DONE', 'AC', 240, now() - INTERVAL '140 days'),
       (3, 15, 'JAVA', 'DONE', 'AC', 305, now() - INTERVAL '135 days'),
       (3, 17, 'JAVA', 'DONE', 'AC', 288, now() - INTERVAL '120 days'),
       (3, 19, 'JAVA', 'DONE', 'TLE', 2000, now() - INTERVAL '100 days'),
       (3, 19, 'JAVA', 'DONE', 'AC', 412, now() - INTERVAL '99 days'),
       (3, 21, 'JAVA', 'DONE', 'AC', 355, now() - INTERVAL '90 days'),
       -- unsolved: dp and shortest-path keep beating him
       (3, 16, 'JAVA', 'DONE', 'WA', 190, now() - INTERVAL '60 days'),
       (3, 16, 'JAVA', 'DONE', 'TLE', 2000, now() - INTERVAL '59 days'),
       (3, 22, 'JAVA', 'DONE', 'WA', 260, now() - INTERVAL '40 days'),
       (3, 23, 'JAVA', 'DONE', 'WA', 310, now() - INTERVAL '30 days'),
       (3, 23, 'JAVA', 'DONE', 'WA', 298, now() - INTERVAL '29 days'),
       (3, 24, 'JAVA', 'DONE', 'TLE', 3000, now() - INTERVAL '14 days');

-- ---------------------------------------------------------------------------------------
-- carol
-- ---------------------------------------------------------------------------------------
INSERT INTO submissions (user_id, problem_id, language, status, verdict, runtime_ms, submitted_at)
VALUES (4, 1, 'CPP', 'DONE', 'AC', 12, now() - INTERVAL '420 days'),
       (4, 3, 'CPP', 'DONE', 'AC', 9, now() - INTERVAL '415 days'),
       (4, 5, 'CPP', 'DONE', 'AC', 14, now() - INTERVAL '410 days'),
       (4, 8, 'CPP', 'DONE', 'AC', 8, now() - INTERVAL '405 days'),
       (4, 10, 'CPP', 'DONE', 'AC', 11, now() - INTERVAL '400 days'),
       (4, 11, 'CPP', 'DONE', 'AC', 19, now() - INTERVAL '390 days'),
       (4, 13, 'CPP', 'DONE', 'AC', 27, now() - INTERVAL '380 days'),
       (4, 14, 'CPP', 'DONE', 'AC', 35, now() - INTERVAL '370 days'),
       (4, 15, 'CPP', 'DONE', 'AC', 41, now() - INTERVAL '360 days'),
       (4, 16, 'CPP', 'DONE', 'AC', 22, now() - INTERVAL '350 days'),
       (4, 17, 'CPP', 'DONE', 'AC', 38, now() - INTERVAL '340 days'),
       (4, 19, 'CPP', 'DONE', 'AC', 66, now() - INTERVAL '320 days'),
       (4, 20, 'CPP', 'DONE', 'WA', 180, now() - INTERVAL '311 days'),
       (4, 20, 'CPP', 'DONE', 'AC', 174, now() - INTERVAL '310 days'),
       (4, 21, 'CPP', 'DONE', 'AC', 57, now() - INTERVAL '300 days'),
       (4, 22, 'CPP', 'DONE', 'AC', 48, now() - INTERVAL '280 days'),
       (4, 23, 'CPP', 'DONE', 'AC', 73, now() - INTERVAL '270 days'),
       (4, 24, 'CPP', 'DONE', 'AC', 145, now() - INTERVAL '250 days'),
       (4, 25, 'CPP', 'DONE', 'AC', 210, now() - INTERVAL '230 days'),
       (4, 26, 'CPP', 'DONE', 'AC', 190, now() - INTERVAL '210 days'),
       (4, 29, 'CPP', 'DONE', 'AC', 480, now() - INTERVAL '170 days'),
       (4, 31, 'CPP', 'DONE', 'AC', 390, now() - INTERVAL '150 days'),
       (4, 32, 'CPP', 'DONE', 'AC', 420, now() - INTERVAL '120 days'),
       (4, 33, 'CPP', 'DONE', 'AC', 310, now() - INTERVAL '100 days'),
       (4, 34, 'CPP', 'DONE', 'AC', 275, now() - INTERVAL '80 days'),
       -- unsolved: geometry is the gap
       (4, 30, 'CPP', 'DONE', 'WA', 260, now() - INTERVAL '45 days'),
       (4, 30, 'CPP', 'DONE', 'WA', 255, now() - INTERVAL '44 days'),
       (4, 37, 'CPP', 'DONE', 'TLE', 3000, now() - INTERVAL '20 days'),
       (4, 36, 'CPP', 'DONE', 'WA', 610, now() - INTERVAL '8 days');

-- ---------------------------------------------------------------------------------------
-- Derived per-user, per-tag counters.
--
-- Counted at *problem* granularity, not submission granularity: five WA submissions on one
-- problem is one attempt, not five. That keeps solved/(attempts + k) a proficiency signal
-- rather than a measure of how stubborn the user is.
-- ---------------------------------------------------------------------------------------
INSERT INTO user_tag_stats (user_id, tag_id, solved_count, attempt_count)
SELECT attempted.user_id,
       attempted.tag_id,
       COUNT(*) FILTER (WHERE attempted.solved) AS solved_count,
       COUNT(*)                                 AS attempt_count
FROM (SELECT s.user_id,
             pt.tag_id,
             bool_or(s.verdict = 'AC') AS solved
      FROM submissions s
               JOIN problem_tags pt ON pt.problem_id = s.problem_id
      GROUP BY s.user_id, pt.tag_id, s.problem_id) AS attempted
GROUP BY attempted.user_id, attempted.tag_id;
