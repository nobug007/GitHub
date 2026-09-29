--[[
ExportServiceProvider.lua - renders a corrected photo out to disk as JPEG/TIFF.

Naming note (intentional deviation from the literal spec filename - see README.md "Lightroom
SDK limitations"): a real `LrExportServiceProvider` is a plugin hook that adds a custom
*destination* to Lightroom's interactive Export dialog (File > Export...) - it is a UI
extension point, triggered by a person clicking Export, not something a plugin can drive
programmatically in the background. That is the wrong tool for headless batch export. This
file keeps the requested name for discoverability, but its actual content is a plain helper
module around `LrExportSession`, which IS the documented, correct API for a plugin to render
photos to disk on its own initiative (see TaskRunner.lua for the caller).

Export setting key names (LR_format, LR_jpeg_quality, LR_export_destinationType, etc.) are
extremely well-established in the Lightroom plugin developer community, but this session
could not pull a single authoritative citation confirming every exact spelling - see the
README's confidence notes. If a given Lightroom Classic version rejects one of these keys,
LrExportSession's constructor will raise a Lua error that TaskRunner.lua catches and reports
per-file, rather than silently corrupting output.
]]

local LrPathUtils = import 'LrPathUtils'
local LrExportSession = import 'LrExportSession'

local Exporter = {}

local function buildExportSettings(request)
    local settings = {
        LR_export_destinationType = 'specificFolder',
        LR_export_destinationPathPrefix = request.outputFolder,
        LR_export_useSubfolder = false,
        LR_reimportExportedPhoto = false,
        -- 'overwrite' rather than 'rename' so the output filename is always predictable
        -- (baseName + extension), which is what the Windows app's result list expects.
        LR_collisionHandling = 'overwrite',
        LR_export_colorSpace = 'sRGB',
        LR_outputSharpeningOn = false,
        LR_size_doConstrain = false,
        LR_export_videoFileHandling = 'exclude',
        LR_useWatermark = false,
        LR_minimizeEmbeddedMetadata = false,
        LR_removeLocationMetadata = false,
        LR_embeddedMetadataOption = 'all',
    }

    if request.format == 'tiff' then
        settings.LR_format = 'TIFF'
        settings.LR_tiff_compressionMethod = 'compressionMethod_ZIP'
        settings.LR_export_bitDepth = 8
    else
        settings.LR_format = 'JPEG'
        -- LR_jpeg_quality is a 0.0-1.0 float in the SDK, not the 1-100 scale the job JSON and
        -- the Windows UI use - convert here, once, in the one place that needs to know that.
        local quality = tonumber(request.quality) or 90
        settings.LR_jpeg_quality = math.max(0, math.min(1, quality / 100))
    end

    return settings
end

--- Renders `photo` (an LrPhoto, already carrying the Develop settings applied by
--- DevelopSettings.build/applyDevelopSettings) to request.outputFolder. Must be called from
--- inside an async task (LrExportSession:doExportOnCurrentTask() blocks/yields).
--- Returns the expected output path (baseName + the requested extension).
function Exporter.exportOne(photo, sourcePath, request)
    local exportSettings = buildExportSettings(request)

    local session = LrExportSession({
        photosToExport = { photo },
        exportSettings = exportSettings,
    })

    session:doExportOnCurrentTask()

    local baseName = LrPathUtils.removeExtension(LrPathUtils.leafName(sourcePath))
    local extension = (request.format == 'tiff') and 'tif' or 'jpg'
    return LrPathUtils.child(request.outputFolder, baseName .. '.' .. extension)
end

return Exporter
