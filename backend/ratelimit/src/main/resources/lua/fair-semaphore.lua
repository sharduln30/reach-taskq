-- Distributed semaphore via sorted set. Each holder is represented by a
-- (member = lease_token, score = wall_clock_ms). Stale holders (whose lease
-- has expired without a release) are pruned at the start of every call so
-- a worker crash cannot leak a slot forever.
--
-- KEYS[1] = zset key (one per tenant)
-- ARGV[1] = max concurrency
-- ARGV[2] = now_ms
-- ARGV[3] = lease_ttl_ms
-- ARGV[4] = member (lease token; opaque string)
--
-- Returns: 1 if slot acquired, 0 if full.

local max     = tonumber(ARGV[1])
local now_ms  = tonumber(ARGV[2])
local ttl_ms  = tonumber(ARGV[3])
local member  = ARGV[4]

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now_ms - ttl_ms)

if redis.call('ZCARD', KEYS[1]) >= max then
    return 0
end

redis.call('ZADD', KEYS[1], now_ms, member)
redis.call('PEXPIRE', KEYS[1], ttl_ms * 2)
return 1
