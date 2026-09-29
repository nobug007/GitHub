--[[
PluginInit.lua - runs once when Lightroom Classic loads the plugin (Info.lua's LrInitPlugin
hook). Starts the background watcher task that polls the jobs folder and writes the
heartbeat the Windows app uses for its CONNECTED/DISCONNECTED indicator.

If this plugin is disabled in Plug-in Manager, Lightroom simply never runs this file, so
there is nothing else to guard here.
]]

local LrTasks = import 'LrTasks'
local LrPrefs = import 'LrPrefs'

local prefs = LrPrefs.prefsForPlugin()
if prefs.autoRunEnabled == nil then
    prefs.autoRunEnabled = true
end
if prefs.pollIntervalSeconds == nil then
    prefs.pollIntervalSeconds = 2
end

LrTasks.startAsyncTask(function()
    local TaskRunner = require 'TaskRunner'
    TaskRunner.runForever()
end, 'MongLightroomBridge background watcher')
