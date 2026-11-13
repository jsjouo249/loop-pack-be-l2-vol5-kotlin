-- Caller must close admission and confirm all old executions and DB work have ended.
if not ARGV[1] or ARGV[1] == '' then
    return redis.error_reply('INVALID_COUPON_PRIMARY')
end

local tokenType = redis.call('TYPE', KEYS[1]).ok
local stateType = redis.call('TYPE', KEYS[2]).ok
if (tokenType ~= 'none' and tokenType ~= 'set')
    or (stateType ~= 'none' and stateType ~= 'string') then
    return -1
end

redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], ARGV[1])
return 1
