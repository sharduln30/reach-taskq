-- Atomic token bucket. Refills tokens lazily on every call based on
-- (now - last_ts) * rps / 1000, capped at burst, then attempts to consume.
--
-- KEYS[1] = bucket key (one per tenant)
-- ARGV[1] = rps (steady refill rate, tokens/sec)
-- ARGV[2] = burst (bucket capacity)
-- ARGV[3] = now_ms (caller's wall clock)
-- ARGV[4] = requested tokens (usually 1)
--
-- Returns: { allowed (0|1), tokens_remaining (int), retry_after_ms (int) }

local rps    = tonumber(ARGV[1])
local burst  = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local req    = tonumber(ARGV[4])

local data   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
    tokens = burst
    ts = now_ms
end

local elapsed = now_ms - ts
if elapsed < 0 then elapsed = 0 end
local refill = elapsed * rps / 1000.0
tokens = math.min(burst, tokens + refill)

local allowed = 0
local retry_ms = 0
if tokens >= req then
    tokens = tokens - req
    allowed = 1
else
    retry_ms = math.ceil((req - tokens) * 1000.0 / rps)
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
-- TTL = time to fully refill an empty bucket * 2, with a 2s floor so the
-- key is reaped after a tenant goes silent but never collides with itself.
local ttl_ms = math.max(2000, math.ceil(burst * 2000.0 / rps))
redis.call('PEXPIRE', KEYS[1], ttl_ms)

return { allowed, math.floor(tokens), retry_ms }
