-- Token bucket, evaluated atomically inside Redis.
--
-- The whole read-refill-consume-write sequence has to be one indivisible step. Doing it with
-- separate GET/SET round trips would let two concurrent requests both read the same token count
-- and both spend it, so the limit would leak under exactly the load it exists to control. A
-- script is Redis's unit of atomicity, so this is the fix - not a WATCH/MULTI retry loop.
--
-- Time comes from redis.call('TIME'), not from the caller. With several application replicas
-- the callers' clocks can disagree by seconds, and a bucket refilled against a fast clock hands
-- out free tokens. The server is the one clock every replica already shares.
--
-- KEYS[1] bucket key
-- ARGV[1] capacity, in tokens
-- ARGV[2] refill rate, in tokens per millisecond
-- ARGV[3] key TTL in milliseconds
--
-- returns { allowed (0|1), whole tokens remaining, retry-after in milliseconds }

local key          = KEYS[1]
local capacity     = tonumber(ARGV[1])
local refillPerMs  = tonumber(ARGV[2])
local ttlMs        = tonumber(ARGV[3])

local time  = redis.call('TIME')
local nowMs = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

local stored    = redis.call('HMGET', key, 'tokens', 'ts')
local tokens    = tonumber(stored[1])
local updatedAt = tonumber(stored[2])

if tokens == nil or updatedAt == nil then
    -- A bucket starts full: a user's first request must never be the one that is refused.
    tokens = capacity
    updatedAt = nowMs
end

local elapsedMs = nowMs - updatedAt
if elapsedMs < 0 then
    elapsedMs = 0
end

tokens = math.min(capacity, tokens + (elapsedMs * refillPerMs))

local allowed = 0
local retryAfterMs = 0

if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retryAfterMs = math.ceil((1 - tokens) / refillPerMs)
    if retryAfterMs < 1 then
        retryAfterMs = 1
    end
end

redis.call('HSET', key, 'tokens', tokens, 'ts', nowMs)
-- Expiry rather than a sweep: an idle bucket refills to full anyway, so forgetting it is
-- equivalent to keeping it, and it costs nothing to store.
redis.call('PEXPIRE', key, ttlMs)

return { allowed, math.floor(tokens), retryAfterMs }
