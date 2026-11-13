if not ready(KEYS[1]) or not typed(KEYS[2], 'stream', false) then return unavailable() end
redis.call('XACK', KEYS[2], ARGV[1], ARGV[2])
return '{}'
