--[[
PluginManager.lua - implements LrPluginInfoProvider (registered via Info.lua's
LrPluginInfoProvider field) so the plugin's settings show up as a section inside Lightroom
Classic's Plug-in Manager dialog (File > Plug-in Manager...).

Lets a person change the jobs-folder path and the auto-run/poll-interval behaviour without
editing any files by hand, per the spec's "플러그인 설치 경로와 작업 폴더 경로는 설정 화면에서
변경 가능해야 한다" requirement. (The plugin's own *install* location is fixed once by where
Lightroom loaded the .lrdevplugin from - see README.md for how to relocate it - only the jobs
folder it watches is meaningfully "configurable" from inside Lightroom.)
]]

local LrView = import 'LrView'
local LrPrefs = import 'LrPrefs'
local LrTasks = import 'LrTasks'
local LrShell = import 'LrShell'

local TaskRunner = require 'TaskRunner'

local PluginManager = {}

function PluginManager.sectionsForTopOfDialog(f, propertyTable)
    local prefs = LrPrefs.prefsForPlugin()
    local bind = LrView.bind

    return {
        {
            title = 'MongLightroomBridge',
            bind_to_object = prefs,

            f:row {
                f:static_text {
                    title = 'Windows 앱(MongLightroomBridge)이 작성한 작업 파일을 감시하여 '
                        .. '자동 보정과 내보내기를 수행합니다. 원본 사진은 절대 수정하지 않습니다.',
                    width_in_chars = 60,
                    height_in_lines = 2,
                },
            },

            f:spacer { height = 8 },

            f:row {
                f:static_text { title = '작업 폴더 (jobs folder):', width = 150 },
                f:edit_field {
                    value = bind 'jobsFolder',
                    width_in_chars = 48,
                },
            },
            f:row {
                f:static_text { title = '', width = 150 },
                f:static_text {
                    title = '비워두면 기본 경로를 사용합니다: ' .. TaskRunner.defaultJobsFolder(),
                    width_in_chars = 60,
                    height_in_lines = 2,
                },
            },

            f:spacer { height = 8 },

            f:row {
                f:checkbox {
                    title = '자동 실행 (Lightroom이 실행되어 있는 동안 작업 폴더를 자동으로 감시)',
                    value = bind 'autoRunEnabled',
                },
            },

            f:row {
                f:static_text { title = '폴링 주기 (초):', width = 150 },
                f:edit_field {
                    value = bind 'pollIntervalSeconds',
                    width_in_chars = 6,
                    precision = 0,
                },
            },

            f:spacer { height = 8 },

            f:row {
                f:push_button {
                    title = '지금 작업 폴더 확인',
                    action = function()
                        LrTasks.startAsyncTask(function()
                            TaskRunner.pollOnce(true)
                        end, 'MongLightroomBridge manual run (Plug-in Manager)')
                    end,
                },
                f:push_button {
                    title = '작업 폴더 열기',
                    action = function()
                        local folder = (prefs.jobsFolder and prefs.jobsFolder ~= '')
                            and prefs.jobsFolder
                            or TaskRunner.defaultJobsFolder()
                        LrShell.revealInShell(folder)
                    end,
                },
            },
        },
    }
end

return PluginManager
