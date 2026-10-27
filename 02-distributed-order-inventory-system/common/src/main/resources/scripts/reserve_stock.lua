-- Atomically reserve stock for every line of an order.
-- KEYS[i] = stock key, ARGV[i] = quantity wanted
--
-- Returns {1, remaining_1, remaining_2, ...} on success,
--         {0, i} if line i does not have enough stock,
--         {-1, i} if line i has no stock key (unknown product).
-- Either every line is decremented or none is.

for i = 1, #KEYS do
    local stock = redis.call('GET', KEYS[i])
    if not stock then
        return {-1, i}
    end
    if tonumber(stock) < tonumber(ARGV[i]) then
        return {0, i}
    end
end

local result = {1}
for i = 1, #KEYS do
    result[#result + 1] = redis.call('DECRBY', KEYS[i], ARGV[i])
end
return result
