local token = ARGV[2]
local capacity = tonumber(ARGV[3])
if not token or token == ''
    or not capacity or capacity < 1 or capacity % 1 ~= 0 then
    return redis.error_reply('INVALID_COUPON_PERMIT')
end

local tokenType = redis.call('TYPE', KEYS[1]).ok
if (tokenType ~= 'none' and tokenType ~= 'set')
    or redis.call('TYPE', KEYS[2]).ok ~= 'string'
    or redis.call('GET', KEYS[2]) ~= ARGV[1]
    or redis.call('PTTL', KEYS[2]) ~= -1
    or redis.call('PTTL', KEYS[1]) >= 0 then
    return -1
end

if redis.call('SISMEMBER', KEYS[1], token) == 1 then
    return 1
end

if redis.call('SCARD', KEYS[1]) >= capacity then
    return 0
end

return redis.call('SADD', KEYS[1], token)
