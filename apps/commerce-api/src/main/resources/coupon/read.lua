if not ready(KEYS[1]) then return unavailable() end
if ARGV[1] == 'ready' then
    local sequence = integer(redis.call('HGET', KEYS[1], 'sequence'))
    if not typed(KEYS[2], 'stream', false) or not sequence or redis.call('XLEN', KEYS[2]) ~= sequence then
        return unavailable()
    end
    local groups = redis.call('XINFO', 'GROUPS', KEYS[2])
    for _, group in ipairs(groups) do
        for index = 1, #group, 2 do
            if group[index] == 'name' and group[index + 1] == ARGV[2] then return '{}' end
        end
    end
    return unavailable()
end
if not positive(ARGV[3]) or not ownerHealthy(KEYS[1], KEYS[2], ARGV[3]) then return unavailable() end
if ARGV[1] == 'find' then
    local result = redis.call('HGET', KEYS[2], ARGV[2])
    return result or 'null'
end
if ARGV[1] ~= 'owned' then return unavailable() end
local records = redis.call('HVALS', KEYS[2])
local result = {}
for _, value in ipairs(records) do result[#result + 1] = cjson.decode(value) end
if #result == 0 then return '[]' end
return cjson.encode(result)
