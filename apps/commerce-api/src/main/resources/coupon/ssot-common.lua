-- Shared validation runs before business writes. Every accessed key is supplied through KEYS.
local function typed(key, expected, missing)
    local actual = redis.call('TYPE', key).ok
    return (actual == expected or (missing and actual == 'none')) and redis.call('PTTL', key) < 0
end

local function ready(meta)
    return typed(meta, 'hash', false)
        and redis.call('HGET', meta, 'schema') == '1'
        and redis.call('HGET', meta, 'health') == 'READY'
end

local function positive(value)
    return type(value) == 'string' and string.match(value, '^[1-9]%d*$') ~= nil
end

local function integer(value)
    if value ~= '0' and not positive(value) then return nil end
    local number = tonumber(value)
    if not number or number < 0 or number > 9007199254740990 or number % 1 ~= 0 then return nil end
    return number
end

local function unavailable()
    return redis.error_reply('COUPON_STATE_UNAVAILABLE')
end

local function ownerHealthy(meta, owner, user)
    if not typed(owner, 'hash', true) then return false end
    local expected = redis.call('HGET', meta, 'owner:' .. user)
    if not expected then return redis.call('EXISTS', owner) == 0 end
    local count = integer(expected)
    return count and count > 0 and redis.call('HLEN', owner) == count
end
