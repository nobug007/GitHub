--[[
Info.lua - the Lightroom Classic plugin manifest. Required fields and shape are documented in
the Lightroom Classic SDK Programmer's Guide, "Plug-in structure" chapter.
]]

return {
    -- 9.0 / 6.0 are deliberately conservative, confirmed-real values (matching a working
    -- published example plugin) rather than a guess at "the latest" SDK number - see
    -- README.md "Lightroom SDK limitations" for why, and how to raise LrSdkVersion if you
    -- want to target newer SDK-only features once you've checked your installed Lightroom
    -- Classic's own SDK guide for its current number.
    LrSdkVersion = 9.0,
    LrSdkMinimumVersion = 6.0,

    LrToolkitIdentifier = 'com.monglightroombridge.plugin',
    LrPluginName = 'MongLightroomBridge',

    -- Runs once when Lightroom loads/enables the plugin. This is the plugin's "auto-start"
    -- hook - see PluginInit.lua, which kicks off the background jobs-folder watcher.
    LrInitPlugin = 'PluginInit.lua',

    -- Registers PluginManager.lua's sectionsForTopOfDialog as a section inside File > Plug-in
    -- Manager, so the jobs-folder path and auto-run settings are editable from Lightroom's UI.
    LrPluginInfoProvider = 'PluginManager.lua',

    LrLibraryMenuItems = {
        {
            title = 'MongLightroomBridge: 지금 작업 확인',
            file = 'RunNow.lua',
        },
        {
            title = 'MongLightroomBridge: 작업 폴더 열기',
            file = 'OpenLogsFolder.lua',
        },
    },

    VERSION = { major = 0, minor = 1, revision = 0, build = 0 },
}
