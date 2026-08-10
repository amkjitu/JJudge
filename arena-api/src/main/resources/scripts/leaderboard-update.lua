-- Updates one member's score, but only in a sorted set that already exists.
--
-- A plain ZADD creates the key when it is missing, which turns a cold cache into a warm cache
-- holding exactly one player. Reads then find a non-empty set, conclude the cache is populated
-- and never rebuild - so the leaderboard shows whoever most recently changed rating, and nobody
-- else, until something evicts the key.
--
-- Skipping the write instead leaves the cache cold, and the next read repopulates it from
-- PostgreSQL, which is the source of truth and already holds the new rating. A dropped write
-- costs nothing here; a partial cache that looks complete costs correctness.
--
-- EXISTS and ZADD have to be one script rather than two calls: between a check and a write the
-- key can be evicted, and the write would recreate exactly the single-entry set this is meant
-- to prevent.
--
-- KEYS[1] = sorted set key
-- ARGV[1] = score (rating)
-- ARGV[2] = member (username)
--
-- Returns 1 when the score was written, 0 when the cache was cold and the write was skipped.

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
return 1
