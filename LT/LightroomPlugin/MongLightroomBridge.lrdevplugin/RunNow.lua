--[[
RunNow.lua - Library menu command ("MongLightroomBridge: 지금 작업 확인"). Manually triggers one
poll of the jobs folder immediately, instead of waiting for the next background-watcher tick.
Mainly useful right after enabling the plugin, or when troubleshooting.
]]

local LrTasks = import 'LrTasks'

LrTasks.startAsyncTask(function()
    local TaskRunner = require 'TaskRunner'
    TaskRunner.pollOnce(true)
end, 'MongLightroomBridge manual run')
