local meta, campaign, events = KEYS[1], KEYS[2], KEYS[3]
local limit = integer(ARGV[4])
if not positive(ARGV[1]) or not ARGV[2] or ARGV[2] == '' or not ARGV[3] or ARGV[3] == ''
    or not limit or limit < 1 or (ARGV[5] ~= '0' and ARGV[5] ~= '1') then
    return unavailable()
end
if not typed(campaign, 'hash', true) or not typed(events, 'stream', true) then return unavailable() end

local fresh = redis.call('EXISTS', meta) == 0
if fresh then
    if ARGV[6] ~= '1' or redis.call('EXISTS', campaign, events) ~= 0 then return unavailable() end
else
    if not ready(meta) or not typed(events, 'stream', false) then return unavailable() end
    local registered = redis.call('HGET', meta, 'campaign:' .. ARGV[1])
    local exists = redis.call('EXISTS', campaign) == 1
    if (registered == '1') ~= exists or (registered and registered ~= '1') then return unavailable() end
    if exists then
        if redis.call('HGET', campaign, 'name') ~= ARGV[2]
            or redis.call('HGET', campaign, 'benefit') ~= ARGV[3]
            or redis.call('HGET', campaign, 'limit') ~= ARGV[4] then return unavailable() end
        return '{}'
    end
end

-- A failed later write leaves DIRTY. Explicit admission reopening never clears this marker.
redis.call('HSET', meta, 'schema', '1', 'health', 'DIRTY')
if fresh then
    redis.call('HSET', meta, 'sequence', '0')
    redis.call('XGROUP', 'CREATE', events, ARGV[7], '0', 'MKSTREAM')
end
redis.call('HSET', meta, 'campaign:' .. ARGV[1], '1')
redis.call('HSET', campaign, 'name', ARGV[2], 'benefit', ARGV[3], 'limit', ARGV[4],
    'enabled', ARGV[5], 'issued', '0')
redis.call('HSET', meta, 'health', 'READY')
return '{}'
