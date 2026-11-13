local meta, campaign, owner, events, permits, state = unpack(KEYS)
if not ready(meta) or not typed(campaign, 'hash', true) or not typed(owner, 'hash', true)
    or not typed(events, 'stream', false) or not typed(permits, 'set', false)
    or not typed(state, 'string', false) or redis.call('GET', state) ~= ARGV[5]
    or redis.call('SISMEMBER', permits, ARGV[4]) ~= 1 then return unavailable() end
if not positive(ARGV[1]) or not positive(ARGV[2]) or not ARGV[3] or ARGV[3] == ''
    or not ownerHealthy(meta, owner, ARGV[1]) then return unavailable() end

local existing = redis.call('HGET', owner, ARGV[2])
if existing then return cjson.encode({status = 'EXISTING', coupon = cjson.decode(existing)}) end
local registered = redis.call('HGET', meta, 'campaign:' .. ARGV[2])
if redis.call('EXISTS', campaign) == 0 then
    if registered then return unavailable() end
    return '{"status":"MISSING"}'
end
if registered ~= '1' then return unavailable() end

local limit = integer(redis.call('HGET', campaign, 'limit'))
local issued = integer(redis.call('HGET', campaign, 'issued'))
local sequence = integer(redis.call('HGET', meta, 'sequence'))
local enabled = redis.call('HGET', campaign, 'enabled')
local benefit = redis.call('HGET', campaign, 'benefit')
local name = redis.call('HGET', campaign, 'name')
if not limit or limit < 1 or not issued or issued > limit or not sequence or sequence >= 9007199254740990
    or (enabled ~= '0' and enabled ~= '1') or not benefit or benefit == '' or not name or name == '' then
    return unavailable()
end
if enabled == '0' then return '{"status":"INACTIVE"}' end
if issued >= limit then return '{"status":"SOLD_OUT"}' end

-- Compute and serialize before writing. IDs are decimal strings to avoid JSON floating-point conversion.
local nextId = string.format('%.0f', sequence + 1)
local fact = {id = nextId, userId = ARGV[1], campaignId = ARGV[2], issuedAt = ARGV[3],
    benefitDescription = benefit, campaignName = name, issuanceLimit = tostring(redis.call('HGET', campaign, 'limit'))}
local payload = cjson.encode(fact)
local response = cjson.encode({status = 'CREATED', coupon = fact})

redis.call('HSET', meta, 'health', 'DIRTY')
redis.call('HSET', meta, 'sequence', nextId)
redis.call('HINCRBY', campaign, 'issued', 1)
redis.call('HINCRBY', meta, 'owner:' .. ARGV[1], 1)
redis.call('HSET', owner, ARGV[2], payload)
redis.call('XADD', events, '*', 'fact', payload)
redis.call('HSET', meta, 'health', 'READY')
return response
