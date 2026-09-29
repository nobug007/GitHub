--[[
OpenLogsFolder.lua - Library menu command ("MongLightroomBridge: 작업 폴더 열기"). Reveals the
configured jobs folder (pending/status/log/heartbeat files) in Explorer, so a person can look
at what the plugin and the Windows app are actually writing to each other without needing to
know the path by heart.
]]

local LrShell = import 'LrShell'
local LrFileUtils = import 'LrFileUtils'

local TaskRunner = require 'TaskRunner'

local folder = TaskRunner.jobsFolder()

if LrFileUtils.exists(folder) ~= 'directory' then
    LrFileUtils.createAllDirectories(folder)
end

LrShell.revealInShell(folder)
