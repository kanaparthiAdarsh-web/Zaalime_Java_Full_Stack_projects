-- Put reserved stock back (failed publish, failed order, ...).
-- KEYS[i] = stock key, ARGV[i] = quantity to return.
-- Returns the new level per key, or -1 when the key is gone
-- (we never recreate a key here, the startup sync owns that).

local result = {}
for i = 1, #KEYS do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        result[i] = redis.call('INCRBY', KEYS[i], ARGV[i])
    else
        result[i] = -1
    end
end
return result
