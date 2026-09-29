--[[
JobFile.lua - file-based IPC with the MongLightroomBridge Windows app (spec section C).

This mirrors the C# side's MongLightroomBridge.Infrastructure.Jobs.JobFileService almost
exactly: pending-{jobId}.json is written by the Windows app and consumed here; status-
{jobId}.json and log-{jobId}.log are written here and polled by the Windows app;
heartbeat.json is written here on every poll tick so the Windows app can show a
CONNECTED/DISCONNECTED indicator. All writes go through a temp-file-then-rename so a reader
on either side never observes a half-written file.

No network/HTTP is involved in this first implementation - see docs/architecture.md for the
localhost-bridge extension point left for later.
]]

local LrFileUtils = import 'LrFileUtils'
local LrPathUtils = import 'LrPathUtils'
local LrDate = import 'LrDate'

local Json = require 'Json'

local JobFile = {}

--- Formats a Unix timestamp (LrDate.currentTime()-style) as an ISO-8601 UTC string, matching
--- the "yyyy-MM-ddTHH:mm:ss.fffZ"-ish shape System.Text.Json produces for DateTimeOffset, so
--- the two sides can compare freshness without needing to agree on a custom format.
function JobFile.isoNow()
    -- LrDate.timeToUserFormat / LrDate.timeToW3CDate: the W3C/ISO-8601 formatter is the
    -- documented, timezone-safe choice here (LrDate.timeToW3CDate(time, useUTC)).
    return LrDate.timeToW3CDate(LrDate.currentTime(), true)
end

local function ensureFolder(folder)
    if LrFileUtils.exists(folder) ~= 'directory' then
        LrFileUtils.createAllDirectories(folder)
    end
end

--- Atomically writes `text` to `path` (temp file + rename), so a concurrent reader on the
--- Windows side never sees a partial file.
local function atomicWrite(path, text)
    ensureFolder(LrPathUtils.parent(path))
    local tempPath = path .. '.tmp'
    local f, openErr = io.open(tempPath, 'wb')
    if not f then
        error('JobFile: could not open ' .. tempPath .. ' for writing: ' .. tostring(openErr))
    end
    f:write(text)
    f:close()

    if LrFileUtils.exists(path) then
        LrFileUtils.delete(path) -- LrFileUtils.move does not itself overwrite an existing destination
    end
    local moved = LrFileUtils.move(tempPath, path)
    if not moved then
        error('JobFile: could not move ' .. tempPath .. ' to ' .. path)
    end
end

local function readFile(path)
    local f, openErr = io.open(path, 'rb')
    if not f then
        return nil, tostring(openErr)
    end
    local content = f:read('*a')
    f:close()
    return content
end

--- Lists pending-*.json job files in `jobsFolder`, oldest first (by filename, which sorts
--- correctly for both the Windows app's jobId scheme and a plain GUID). Returns an array of
--- { jobId = ..., path = ... }.
function JobFile.listPendingJobs(jobsFolder)
    local jobs = {}
    if LrFileUtils.exists(jobsFolder) ~= 'directory' then
        return jobs
    end
    for path in LrFileUtils.files(jobsFolder) do
        local name = LrPathUtils.leafName(path)
        local jobId = name:match('^pending%-(.+)%.json$')
        if jobId then
            table.insert(jobs, { jobId = jobId, path = path })
        end
    end
    table.sort(jobs, function(a, b) return a.jobId < b.jobId end)
    return jobs
end

--- Reads and decodes a pending job file. Returns request, nil on success or nil, errorMessage.
function JobFile.readJob(path)
    local content, readErr = readFile(path)
    if not content then
        return nil, readErr
    end
    local decoded, decodeErr = Json.decode(content)
    if not decoded then
        return nil, decodeErr
    end
    return decoded
end

--- Marks a pending job as claimed by renaming pending-{id}.json -> processing-{id}.json, so a
--- second poll tick (or a restart mid-job) never double-processes the same file. The renamed
--- file is left in place afterwards as a lightweight audit trail; nothing polls for it.
function JobFile.claimJob(jobsFolder, jobId)
    local from = LrPathUtils.child(jobsFolder, 'pending-' .. jobId .. '.json')
    local to = LrPathUtils.child(jobsFolder, 'processing-' .. jobId .. '.json')
    if LrFileUtils.exists(to) then
        LrFileUtils.delete(to)
    end
    return LrFileUtils.move(from, to)
end

function JobFile.writeStatus(jobsFolder, jobId, statusTable)
    local path = LrPathUtils.child(jobsFolder, 'status-' .. jobId .. '.json')
    atomicWrite(path, Json.encode(statusTable))
end

--- Appends one line (prefixed with a UTC timestamp) to log-{jobId}.log. This is a plain
--- append, not atomic-via-rename like the JSON files, matching the C# reader's tolerant
--- "read whatever is there right now" behaviour (JobFileService.ReadLogAsync opens with
--- FileShare.ReadWrite).
function JobFile.appendLog(jobsFolder, jobId, line)
    ensureFolder(jobsFolder)
    local path = LrPathUtils.child(jobsFolder, 'log-' .. jobId .. '.log')
    local f, openErr = io.open(path, 'ab')
    if not f then
        return false, tostring(openErr)
    end
    f:write('[' .. JobFile.isoNow() .. '] ' .. line .. '\n')
    f:close()
    return true
end

--- Writes heartbeat.json - the Windows app treats this file as CONNECTED as long as it was
--- modified within the last ~15 seconds (see MainViewModel.CheckHeartbeatAsync).
function JobFile.writeHeartbeat(jobsFolder, info)
    local path = LrPathUtils.child(jobsFolder, 'heartbeat.json')
    local payload = {
        lastSeenUtc = JobFile.isoNow(),
        lightroomVersion = info.lightroomVersion,
        pluginVersion = info.pluginVersion,
        queuedJobs = info.queuedJobs or 0,
    }
    atomicWrite(path, Json.encode(payload))
end

return JobFile
