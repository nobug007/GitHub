--[[
Log.lua - thin wrapper around Lightroom's own LrLogger, used for plugin-lifecycle and
unexpected-error messages (visible via Help > Editor Log / "Enable logging" in Plug-in
Manager). Per-job progress/results go through JobFile.appendLog instead, since that's what
the Windows app actually reads - this file is a debugging aid on top, not the primary log.
]]

local LrLogger = import 'LrLogger'

local logger = LrLogger('MongLightroomBridge')
-- 'logfile' is the documented destination name in the SDK guide, but this is a debugging aid,
-- not a load-bearing part of the IPC - never let a logging-setup quirk break plugin startup.
pcall(function() logger:enable('logfile') end)

local Log = {}

function Log.info(message, ...)
    logger:infof(message, ...)
end

function Log.warn(message, ...)
    logger:warnf(message, ...)
end

function Log.error(message, ...)
    logger:errorf(message, ...)
end

return Log
