--[[
TaskRunner.lua - the heart of the plugin: watches the jobs folder, and for every pending job,
imports the input photos into the catalog, applies the requested Develop correction, exports
them, and reports status back through JobFile.

SAFETY (spec section "원본 사진은 절대 수정하지 않는다" / never modify original photos): this
file never calls any API that writes to a source file. catalog:addPhoto() only registers the
existing file with the catalog (Lightroom is non-destructive by design - Develop settings are
stored as catalog metadata, not baked into the source until a person explicitly does something
like "Save Metadata to File", which nothing here ever calls). Exporter.exportOne() always
renders to request.outputFolder, never back over the source. request.preserveOriginals is
therefore treated as a fixed safety invariant, not a toggle - even a job that explicitly sets
it to false is still processed exactly as if it were true (and a warning is logged so that is
visible, rather than silently ignoring what the job asked for).
]]

local LrTasks = import 'LrTasks'
local LrApplication = import 'LrApplication'
local LrFileUtils = import 'LrFileUtils'
local LrPathUtils = import 'LrPathUtils'
local LrStringUtils = import 'LrStringUtils'
local LrPrefs = import 'LrPrefs'
local LrDialogs = import 'LrDialogs'

local JobFile = require 'JobFile'
local DevelopSettings = require 'DevelopSettings'
local Exporter = require 'ExportServiceProvider'
local Log = require 'Log'
local Json = require 'Json'

local TaskRunner = {}

local PLUGIN_VERSION = '0.1.0'

local SUPPORTED_EXTENSIONS = {
    jpg = true, jpeg = true, tif = true, tiff = true,
    png = true, dng = true, cr2 = true, cr3 = true,
    nef = true, arw = true, raf = true, orf = true, rw2 = true,
}

local function prefs()
    return LrPrefs.prefsForPlugin()
end

--- %LOCALAPPDATA%\MongLightroomBridge\jobs by default, matching BridgeSettings.DefaultJobsFolder()
--- on the Windows side. Overridable from the Plug-in Manager screen (PluginManager.lua).
function TaskRunner.defaultJobsFolder()
    local base = os.getenv('LOCALAPPDATA')
    if not base or base == '' then
        -- Fall back to the roaming AppData path the SDK does document, one level up from
        -- where LOCALAPPDATA would normally be - better than crashing if the env var is ever
        -- unavailable (it always should be on Windows, but never assume in an automation tool).
        local ok, roaming = pcall(function() return LrPathUtils.getStandardFilePath('appData') end)
        base = ok and roaming or LrPathUtils.getStandardFilePath('temp')
    end
    return LrPathUtils.child(LrPathUtils.child(base, 'MongLightroomBridge'), 'jobs')
end

function TaskRunner.jobsFolder()
    local p = prefs()
    if p.jobsFolder and p.jobsFolder ~= '' then
        return p.jobsFolder
    end
    return TaskRunner.defaultJobsFolder()
end

local function listInputFiles(inputFolder)
    local files = {}
    if LrFileUtils.exists(inputFolder) ~= 'directory' then
        return files
    end
    for path in LrFileUtils.files(inputFolder) do
        local ext = LrStringUtils.lower(LrPathUtils.extension(path) or '')
        if SUPPORTED_EXTENSIONS[ext] then
            table.insert(files, path)
        end
    end
    table.sort(files)
    return files
end

--- Windows-app-only, additive job field (see docs/job-schema.md "excludedFileNames"): a plain
--- array of file names (not full paths) the app's local quality pre-filter (blur / eyes-uncertain)
--- decided to leave out. Nil-safe - older job files (and the spec's own sample job) simply have no
--- such field, and everything below treats that identically to an empty list. Returns a lookup set
--- keyed by lower-cased file name so matching is case-insensitive.
local function buildExcludedFileNameSet(request)
    local set = {}
    if type(request.excludedFileNames) ~= 'table' then
        return set
    end
    for _, name in ipairs(request.excludedFileNames) do
        if type(name) == 'string' then
            set[LrStringUtils.lower(name)] = true
        end
    end
    return set
end

--- Processes every supported file in request.inputFolder for one job, writing status/log
--- updates to the jobs folder as it goes. Never raises - callers get a boolean back.
local function runJob(catalog, jobsFolder, jobId, request)
    local status = {
        jobId = jobId,
        status = 'running',
        startedAtUtc = JobFile.isoNow(),
        totalFiles = 0,
        processedFiles = 0,
        results = Json.array({}),
    }
    JobFile.writeStatus(jobsFolder, jobId, status)
    JobFile.appendLog(jobsFolder, jobId, 'Job started (profile=' .. tostring(request.profile) .. ', format=' .. tostring(request.format) .. ')')

    if request.preserveOriginals == false then
        JobFile.appendLog(jobsFolder, jobId, "WARNING: job requested preserveOriginals=false - ignored. MongLightroomBridge never modifies source files, by design.")
    end

    local completedOk, jobErr = LrTasks.pcall(function()
        if not LrFileUtils.exists(request.outputFolder) then
            LrFileUtils.createAllDirectories(request.outputFolder)
        end

        local files = listInputFiles(request.inputFolder)
        status.totalFiles = #files
        JobFile.writeStatus(jobsFolder, jobId, status)

        if #files == 0 then
            JobFile.appendLog(jobsFolder, jobId, 'No supported image files found in ' .. tostring(request.inputFolder) .. ' - nothing to do.')
        end

        local excludedFileNames = buildExcludedFileNameSet(request)

        for _, sourcePath in ipairs(files) do
            local fileName = LrPathUtils.leafName(sourcePath)

            -- Lightroom's Lua runtime has no goto/labels (Lua 5.1 dialect), so the skip case is
            -- just the other branch of this if/else rather than an early-exit jump.
            if excludedFileNames[LrStringUtils.lower(fileName)] then
                JobFile.appendLog(jobsFolder, jobId, 'SKIPPED (quality filter): ' .. fileName)
                table.insert(status.results, {
                    fileName = fileName,
                    success = false,
                    skipped = true,
                    error = 'Excluded by Windows app quality filter (blurry or eyes-uncertain) before processing.',
                })
            else
                local fileOk, fileErr = LrTasks.pcall(function()
                    local photo
                    catalog:withWriteAccessDo('MongLightroomBridge: import + develop ' .. fileName, function()
                        photo = catalog:addPhoto(sourcePath)
                        local current = photo:getDevelopSettings()
                        local dims = photo:getRawMetadata('dimensions')
                        local newSettings = DevelopSettings.build(current, request.adjustments, dims)
                        photo:applyDevelopSettings(newSettings, 'MongLightroomBridge auto-correction', false)
                    end)

                    local outputPath = Exporter.exportOne(photo, sourcePath, request)
                    return outputPath
                end)

                local resultEntry = { fileName = fileName, success = fileOk }
                if fileOk then
                    resultEntry.outputPath = fileErr -- LrTasks.pcall returns the wrapped function's return value as the 2nd result on success
                    JobFile.appendLog(jobsFolder, jobId, 'OK: ' .. fileName)
                else
                    resultEntry.error = tostring(fileErr)
                    JobFile.appendLog(jobsFolder, jobId, 'FAIL: ' .. fileName .. ' - ' .. tostring(fileErr))
                end
                table.insert(status.results, resultEntry)
            end

            status.processedFiles = status.processedFiles + 1
            JobFile.writeStatus(jobsFolder, jobId, status)
        end
    end)

    if completedOk then
        status.status = 'completed'
    else
        status.status = 'failed'
        status.errorMessage = tostring(jobErr)
        JobFile.appendLog(jobsFolder, jobId, 'Job failed: ' .. tostring(jobErr))
    end
    status.finishedAtUtc = JobFile.isoNow()
    JobFile.writeStatus(jobsFolder, jobId, status)

    return completedOk
end

--- Checks the jobs folder once and processes every pending job found, oldest first.
--- `interactive` (optional): when true (manual "지금 작업 확인" menu command), shows a summary
--- dialog at the end instead of running silently.
function TaskRunner.pollOnce(interactive)
    local jobsFolder = TaskRunner.jobsFolder()
    local pending = JobFile.listPendingJobs(jobsFolder)

    if #pending == 0 then
        if interactive then
            LrDialogs.message('MongLightroomBridge', '대기 중인 작업이 없습니다.', 'info')
        end
        return 0
    end

    local catalog = LrApplication.activeCatalog()
    local processed = 0

    for _, job in ipairs(pending) do
        local claimed = JobFile.claimJob(jobsFolder, job.jobId)
        if claimed then
            local request, readErr = JobFile.readJob(LrPathUtils.child(jobsFolder, 'processing-' .. job.jobId .. '.json'))
            if request then
                Log.info('Processing job %s', job.jobId)
                runJob(catalog, jobsFolder, job.jobId, request)
                processed = processed + 1
            else
                Log.error('Could not read job %s: %s', job.jobId, tostring(readErr))
                JobFile.writeStatus(jobsFolder, job.jobId, {
                    jobId = job.jobId,
                    status = 'failed',
                    errorMessage = '작업 파일을 읽을 수 없습니다: ' .. tostring(readErr),
                    finishedAtUtc = JobFile.isoNow(),
                    results = Json.array({}),
                })
            end
        end
    end

    if interactive then
        LrDialogs.message('MongLightroomBridge', string.format('%d개 작업을 처리했습니다.', processed), 'info')
    end

    return processed
end

--- Runs forever: writes a heartbeat every tick (so the Windows app can show CONNECTED) and,
--- when auto-run is enabled in Plug-in Manager, polls for pending jobs. Meant to be started
--- once from PluginInit.lua inside LrTasks.startAsyncTask.
function TaskRunner.runForever()
    Log.info('MongLightroomBridge background watcher started (version %s)', PLUGIN_VERSION)
    while true do
        local ok, err = LrTasks.pcall(function()
            local jobsFolder = TaskRunner.jobsFolder()
            local p = prefs()
            local queued = #JobFile.listPendingJobs(jobsFolder)

            JobFile.writeHeartbeat(jobsFolder, {
                lightroomVersion = tostring(LrApplication.versionTable and LrApplication.versionTable().major or '?'),
                pluginVersion = PLUGIN_VERSION,
                queuedJobs = queued,
            })

            if p.autoRunEnabled ~= false then
                TaskRunner.pollOnce(false)
            end
        end)

        if not ok then
            Log.error('Background watcher tick failed: %s', tostring(err))
        end

        LrTasks.sleep((prefs().pollIntervalSeconds) or 2)
    end
end

TaskRunner.PLUGIN_VERSION = PLUGIN_VERSION

return TaskRunner
