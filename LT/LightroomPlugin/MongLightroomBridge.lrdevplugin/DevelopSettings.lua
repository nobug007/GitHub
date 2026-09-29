--[[
DevelopSettings.lua - translates a MongLightroomBridge job's `adjustments` table (the same
shape the Windows app sends - see docs/job-schema.md) into a real Lightroom Classic Develop
settings table, suitable for LrPhoto:applyDevelopSettings().

Key names below (Exposure2012, Contrast2012, Highlights2012, Shadows2012, Whites2012,
Blacks2012, Vibrance, Saturation, Temperature, Tint, ConvertToGrayscale, CropTop/Left/Right/
Bottom/Angle) are the real, documented Lightroom Develop setting keys for the "2012 process
version" (PV4/PV5) tone controls. See README.md "Lightroom SDK limitations" for the
confidence level behind each one and what to do if a Lightroom Classic version rejects a key.

IMPORTANT (matches the Windows side's AdjustmentValues.cs doc comments exactly): Exposure/
Contrast/Highlights/Shadows/Whites/Blacks/Vibrance/Saturation are applied as ABSOLUTE slider
positions (this plugin sets the photo's Develop sliders to exactly these values). Temperature
and Tint are applied as DELTAS on top of the photo's current white balance, because
Lightroom's Temperature key is an absolute Kelvin value with no fixed "zero" - there is no
such thing as "the neutral temperature" independent of the shot, so a fixed absolute value
would fight the photographer's as-shot white balance on every single photo. Reading the
photo's current settings first and adding the requested delta is the only sane behaviour.
]]

local DevelopSettings = {}

local function clamp(value, lo, hi)
    if value == nil then
        return nil
    end
    if value < lo then return lo end
    if value > hi then return hi end
    return value
end

--- Computes a centered crop rectangle (normalized 0..1 CropTop/Left/Right/Bottom) that best
--- fits `targetAspect` (e.g. "4:5", "16:9") within a photo of size photoWidth x photoHeight.
--- Returns nil if the aspect string can't be parsed (caller should then skip cropping rather
--- than guess).
function DevelopSettings.computeCrop(targetAspect, photoWidth, photoHeight)
    if not targetAspect or photoWidth == nil or photoHeight == nil or photoWidth <= 0 or photoHeight <= 0 then
        return nil
    end
    local w, h = targetAspect:match('^(%d+%.?%d*):(%d+%.?%d*)$')
    if not w then
        return nil
    end
    w, h = tonumber(w), tonumber(h)
    if not w or not h or w <= 0 or h <= 0 then
        return nil
    end

    local targetRatio = w / h
    local photoRatio = photoWidth / photoHeight

    local top, left, right, bottom
    if photoRatio > targetRatio then
        -- Photo is relatively wider than the target: crop the sides, keep full height.
        local cropWidthFraction = targetRatio / photoRatio
        local margin = (1 - cropWidthFraction) / 2
        top, bottom = 0, 1
        left, right = margin, 1 - margin
    else
        -- Photo is relatively taller than the target: crop top/bottom, keep full width.
        local cropHeightFraction = photoRatio / targetRatio
        local margin = (1 - cropHeightFraction) / 2
        left, right = 0, 1
        top, bottom = margin, 1 - margin
    end

    return { top = top, left = left, right = right, bottom = bottom }
end

--- Builds the Develop settings table to pass to LrPhoto:applyDevelopSettings().
---   currentSettings - the table returned by photo:getDevelopSettings() (used as the base for
---                      the Temperature/Tint delta, and as the fallback for anything this job
---                      doesn't specify).
---   adjustments      - the job's `adjustments` object, already JSON-decoded (camelCase keys:
---                      exposure, contrast, highlights, shadows, whites, blacks, temperature,
---                      tint, vibrance, saturation, cropAspect, convertToGrayscale).
---   photoDimensions  - optional { width = , height = } from photo:getRawMetadata('dimensions'),
---                      required only if adjustments.cropAspect is set.
function DevelopSettings.build(currentSettings, adjustments, photoDimensions)
    currentSettings = currentSettings or {}
    adjustments = adjustments or {}

    local settings = {}

    settings.Exposure2012 = clamp(adjustments.exposure or 0, -5, 5)
    settings.Contrast2012 = clamp(adjustments.contrast or 0, -100, 100)
    settings.Highlights2012 = clamp(adjustments.highlights or 0, -100, 100)
    settings.Shadows2012 = clamp(adjustments.shadows or 0, -100, 100)
    settings.Whites2012 = clamp(adjustments.whites or 0, -100, 100)
    settings.Blacks2012 = clamp(adjustments.blacks or 0, -100, 100)
    settings.Vibrance = clamp(adjustments.vibrance or 0, -100, 100)
    settings.Saturation = clamp(adjustments.saturation or 0, -100, 100)

    local baseTemp = tonumber(currentSettings.Temperature) or 5500
    local baseTint = tonumber(currentSettings.Tint) or 0
    settings.Temperature = clamp(math.floor(baseTemp + (adjustments.temperature or 0) + 0.5), 2000, 50000)
    settings.Tint = clamp(baseTint + (adjustments.tint or 0), -150, 150)
    settings.WhiteBalance = 'Custom'

    if adjustments.convertToGrayscale then
        settings.ConvertToGrayscale = true
    end

    if adjustments.cropAspect and adjustments.cropAspect ~= '' then
        local dims = photoDimensions or {}
        local crop = DevelopSettings.computeCrop(adjustments.cropAspect, dims.width, dims.height)
        if crop then
            settings.CropTop = crop.top
            settings.CropLeft = crop.left
            settings.CropRight = crop.right
            settings.CropBottom = crop.bottom
            settings.CropAngle = 0
            settings.HasCrop = true
        end
    end

    return settings
end

return DevelopSettings
