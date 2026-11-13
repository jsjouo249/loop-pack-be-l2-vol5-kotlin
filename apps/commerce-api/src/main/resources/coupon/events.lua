if not ready(KEYS[1]) or not typed(KEYS[2], 'stream', false) then return unavailable() end
local limit = integer(ARGV[1])
if not limit or limit < 1 or limit > 100 then return unavailable() end
local records = redis.call('XREADGROUP', 'GROUP', ARGV[2], ARGV[3], 'COUNT', limit, 'STREAMS', KEYS[2], '0')
if not records or #records[1][2] == 0 then
    records = redis.call('XREADGROUP', 'GROUP', ARGV[2], ARGV[3], 'COUNT', limit, 'STREAMS', KEYS[2], '>')
end
if not records or #records[1][2] == 0 then return '[]' end
local result = {}
for _, record in ipairs(records[1][2]) do
    if #record[2] ~= 2 or record[2][1] ~= 'fact' then return unavailable() end
    result[#result + 1] = {recordId = record[1], fact = cjson.decode(record[2][2])}
end
return cjson.encode(result)
