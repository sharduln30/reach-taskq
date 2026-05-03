-- Refresh the score of an existing semaphore holder (called from the
-- worker's lease heartbeat). No-op if the member was already pruned —
-- in that case the worker should drop the job and let the lease reaper
-- clean up the row, since it has effectively lost its slot.
--
-- KEYS[1] = zset key
-- ARGV[1] = now_ms
-- ARGV[2] = lease_ttl_ms
-- ARGV[3] = member
--
-- Returns: 1 if refreshed, 0 if the member was no longer present.

local now_ms = tonumber(ARGV[1])
local ttl_ms = tonumber(ARGV[2])
local member = ARGV[3]

if redis.call('ZSCORE', KEYS[1], member) == false then
    return 0
end

redis.call('ZADD', KEYS[1], now_ms, member)
redis.call('PEXPIRE', KEYS[1], ttl_ms * 2)
return 1
