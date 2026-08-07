-- Demo accounts.
--
-- Passwords are real BCrypt(strength 10) hashes, not placeholders:
--   admin / Admin123!
--   alice / Password123!
--   bob   / Password123!
--   carol / Password123!
--
-- These exist so a reviewer can clone the repo and log in immediately. They are seed data
-- for a demo application - never reuse these credentials anywhere that matters.
-- The three non-admin users are deliberately at different skill levels so the recommendation
-- engine produces visibly different output for each.

INSERT INTO users (id, username, email, password_hash, role, rating, created_at)
VALUES (1, 'admin', 'admin@codearena.dev',
        '$2a$10$FKBnGd5cHYUvNIL4F6fC5eYDJJGE67b1Ob/DdhDiPjsMdUghNrWlC', 'ADMIN', 2100,
        now() - INTERVAL '400 days'),
       -- beginner: strong on arrays/strings, has barely touched graphs or dp
       (2, 'alice', 'alice@codearena.dev',
        '$2a$10$9vJPeNO.aIPhfwZAzEoTu.orm2xmCD3NG0dMYlkb0gmj/B.I4KIbG', 'USER', 1150,
        now() - INTERVAL '120 days'),
       -- intermediate: broad but weak on dp and shortest paths
       (3, 'bob', 'bob@codearena.dev',
        '$2a$10$fLj5x/YSwEfCcL3Hfip.6ersV7AHiPOtMnlBbPWcEh5bEoVbwT606', 'USER', 1450,
        now() - INTERVAL '300 days'),
       -- advanced: comfortable nearly everywhere, weak on geometry
       (4, 'carol', 'carol@codearena.dev',
        '$2a$10$SkA8LZBj5.jMbMliM6vFROKXJAiJ73pOyE2ksXNBYmAV2mYJBCE4G', 'USER', 1750,
        now() - INTERVAL '500 days');

SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT MAX(id) FROM users));
