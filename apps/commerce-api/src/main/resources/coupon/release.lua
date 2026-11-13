local token = ARGV[2]
if not token or token == '' then
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

return redis.call('SREM', KEYS[1], token)
