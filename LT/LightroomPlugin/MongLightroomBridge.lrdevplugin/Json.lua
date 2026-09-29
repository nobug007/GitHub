--[[
Json.lua - minimal, dependency-free JSON encode/decode for MongLightroomBridge.

The Lightroom Classic Lua sandbox does not ship a JSON library, and third-party pure-Lua
libraries can't be vendored with full confidence of correctness without a way to unit-test
them inside Lightroom itself. This is a small, hand-written encoder/decoder scoped exactly
to what MongLightroomBridge needs (the job/status/heartbeat JSON schema - see
docs/job-schema.md) rather than a general-purpose library, to keep the surface area (and the
risk of subtle bugs) as small as possible.

Notable simplifications (documented, not accidental):
  * decode() turns JSON null into a plain Lua nil (i.e. the key is simply absent from the
    result table). None of the fields MongLightroomBridge reads are ever meaningfully null,
    so this is safe here.
  * encode() cannot tell an empty Lua table {} apart from an empty JSON array []. Call
    Json.array(t) to explicitly mark a table (including an empty one) as an array; this
    matters for JobStatus.results, which the C# side deserializes as List<JobFileResult> and
    which MUST be "[]", never "{}", when there are no results yet.
]]

local Json = {}

local ARRAY_MARK = {}

-- Marks `t` as a JSON array even if it is empty or Lua can't otherwise infer that from its keys.
function Json.array(t)
    t = t or {}
    setmetatable(t, { __jsonarray = true })
    return t
end

local function isMarkedArray(t)
    local mt = getmetatable(t)
    return mt ~= nil and mt.__jsonarray == true
end

local function isArrayLike(t)
    if isMarkedArray(t) then
        return true
    end
    local count = 0
    for _ in pairs(t) do
        count = count + 1
    end
    if count == 0 then
        return false -- ambiguous; encode as {} unless explicitly Json.array()'d
    end
    for i = 1, count do
        if t[i] == nil then
            return false
        end
    end
    return true
end

local ESCAPES = {
    ['"'] = '\\"',
    ['\\'] = '\\\\',
    ['\b'] = '\\b',
    ['\f'] = '\\f',
    ['\n'] = '\\n',
    ['\r'] = '\\r',
    ['\t'] = '\\t',
}

local function encodeString(s)
    local out = { '"' }
    for i = 1, #s do
        local c = s:sub(i, i)
        local escaped = ESCAPES[c]
        if escaped then
            out[#out + 1] = escaped
        elseif c:byte() < 0x20 then
            out[#out + 1] = string.format('\\u%04x', c:byte())
        else
            out[#out + 1] = c
        end
    end
    out[#out + 1] = '"'
    return table.concat(out)
end

local function encodeNumber(n)
    if n ~= n or n == math.huge or n == -math.huge then
        return '0' -- NaN/Infinity have no JSON representation; fail safe rather than emit invalid JSON
    end
    if math.floor(n) == n and math.abs(n) < 1e15 then
        return string.format('%d', n)
    end
    return string.format('%.10g', n)
end

local encodeValue

local function encodeObject(t)
    local parts = {}
    for k, v in pairs(t) do
        if type(k) == 'string' then
            parts[#parts + 1] = encodeString(k) .. ':' .. encodeValue(v)
        end
    end
    return '{' .. table.concat(parts, ',') .. '}'
end

local function encodeArray(t)
    local parts = {}
    local n = 0
    for _ in pairs(t) do
        n = n + 1
    end
    for i = 1, n do
        parts[#parts + 1] = encodeValue(t[i])
    end
    return '[' .. table.concat(parts, ',') .. ']'
end

encodeValue = function(v)
    local t = type(v)
    if v == nil then
        return 'null'
    elseif t == 'string' then
        return encodeString(v)
    elseif t == 'number' then
        return encodeNumber(v)
    elseif t == 'boolean' then
        return v and 'true' or 'false'
    elseif t == 'table' then
        if isArrayLike(v) then
            return encodeArray(v)
        end
        return encodeObject(v)
    end
    error('Json.encode: unsupported value type ' .. t)
end

function Json.encode(value)
    return encodeValue(value)
end

-- ===== Decoder =====

local function newParser(text)
    return { text = text, pos = 1, len = #text }
end

local function parseError(p, message)
    error(string.format('Json.decode: %s at position %d', message, p.pos))
end

local function skipWhitespace(p)
    local _, stop = p.text:find('^[ \t\r\n]*', p.pos)
    if stop then
        p.pos = stop + 1
    end
end

local parseValue

local function parseLiteral(p, literal, value)
    if p.text:sub(p.pos, p.pos + #literal - 1) == literal then
        p.pos = p.pos + #literal
        return value
    end
    parseError(p, 'expected ' .. literal)
end

local function parseString(p)
    if p.text:sub(p.pos, p.pos) ~= '"' then
        parseError(p, 'expected string')
    end
    p.pos = p.pos + 1
    local out = {}
    while true do
        local c = p.text:sub(p.pos, p.pos)
        if c == '' then
            parseError(p, 'unterminated string')
        elseif c == '"' then
            p.pos = p.pos + 1
            return table.concat(out)
        elseif c == '\\' then
            local esc = p.text:sub(p.pos + 1, p.pos + 1)
            if esc == 'u' then
                local hex = p.text:sub(p.pos + 2, p.pos + 5)
                local code = tonumber(hex, 16) or 63 -- '?' fallback
                if code < 0x80 then
                    out[#out + 1] = string.char(code)
                elseif code < 0x800 then
                    out[#out + 1] = string.char(0xC0 + math.floor(code / 0x40), 0x80 + (code % 0x40))
                else
                    out[#out + 1] = string.char(
                        0xE0 + math.floor(code / 0x1000),
                        0x80 + (math.floor(code / 0x40) % 0x40),
                        0x80 + (code % 0x40))
                end
                p.pos = p.pos + 6
            else
                local map = { ['"'] = '"', ['\\'] = '\\', ['/'] = '/', b = '\b', f = '\f', n = '\n', r = '\r', t = '\t' }
                out[#out + 1] = map[esc] or esc
                p.pos = p.pos + 2
            end
        else
            out[#out + 1] = c
            p.pos = p.pos + 1
        end
    end
end

local function parseNumber(p)
    local s, e = p.text:find('^-?%d+%.?%d*[eE]?[%+%-]?%d*', p.pos)
    if not s then
        parseError(p, 'expected number')
    end
    local numStr = p.text:sub(s, e)
    p.pos = e + 1
    return tonumber(numStr)
end

local function parseArray(p)
    p.pos = p.pos + 1 -- consume '['
    local result = Json.array({})
    skipWhitespace(p)
    if p.text:sub(p.pos, p.pos) == ']' then
        p.pos = p.pos + 1
        return result
    end
    local i = 0
    while true do
        skipWhitespace(p)
        local value = parseValue(p)
        i = i + 1
        result[i] = value
        skipWhitespace(p)
        local c = p.text:sub(p.pos, p.pos)
        if c == ',' then
            p.pos = p.pos + 1
        elseif c == ']' then
            p.pos = p.pos + 1
            return result
        else
            parseError(p, "expected ',' or ']'")
        end
    end
end

local function parseObject(p)
    p.pos = p.pos + 1 -- consume '{'
    local result = {}
    skipWhitespace(p)
    if p.text:sub(p.pos, p.pos) == '}' then
        p.pos = p.pos + 1
        return result
    end
    while true do
        skipWhitespace(p)
        local key = parseString(p)
        skipWhitespace(p)
        if p.text:sub(p.pos, p.pos) ~= ':' then
            parseError(p, "expected ':'")
        end
        p.pos = p.pos + 1
        skipWhitespace(p)
        local value = parseValue(p)
        result[key] = value -- JSON null decodes to Lua nil: this is a no-op assignment, by design (see header comment)
        skipWhitespace(p)
        local c = p.text:sub(p.pos, p.pos)
        if c == ',' then
            p.pos = p.pos + 1
        elseif c == '}' then
            p.pos = p.pos + 1
            return result
        else
            parseError(p, "expected ',' or '}'")
        end
    end
end

parseValue = function(p)
    skipWhitespace(p)
    local c = p.text:sub(p.pos, p.pos)
    if c == '{' then
        return parseObject(p)
    elseif c == '[' then
        return parseArray(p)
    elseif c == '"' then
        return parseString(p)
    elseif c == 't' then
        return parseLiteral(p, 'true', true)
    elseif c == 'f' then
        return parseLiteral(p, 'false', false)
    elseif c == 'n' then
        return parseLiteral(p, 'null', nil)
    elseif c == '-' or c:match('%d') then
        return parseNumber(p)
    end
    parseError(p, 'unexpected character ' .. (c == '' and '<eof>' or c))
end

--- Decodes a JSON text into Lua values. Returns nil, errorMessage on malformed input instead
--- of raising, so callers (which are usually mid-poll-loop and must not crash) can treat a
--- partially-written file as "not ready yet" - see JobFile.lua.
function Json.decode(text)
    local p = newParser(text)
    local ok, result = pcall(function()
        local value = parseValue(p)
        skipWhitespace(p)
        if p.pos <= p.len then
            parseError(p, 'trailing content after JSON value')
        end
        return value
    end)
    if ok then
        return result
    end
    return nil, result
end

return Json
