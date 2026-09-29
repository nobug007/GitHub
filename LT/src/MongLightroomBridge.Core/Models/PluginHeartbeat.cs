namespace MongLightroomBridge.Core.Models;

/// <summary>
/// heartbeat.json, written by the Lua plugin's background task (TaskRunner.lua) roughly every few
/// seconds while Lightroom is running with the plugin enabled. This is how the Windows app answers
/// "is Lightroom running / is the plugin responding" (spec: "Windows 앱은 Lightroom이 실행 중인지/
/// 플러그인이 응답하는지 표시한다") without any socket - it just checks how stale this file is.
/// </summary>
public sealed class PluginHeartbeat
{
    public DateTimeOffset LastSeenUtc { get; set; }

    public string? LightroomVersion { get; set; }

    public string? PluginVersion { get; set; }

    /// <summary>Number of pending-*.json files the plugin saw sitting in the jobs folder at last heartbeat.</summary>
    public int QueuedJobs { get; set; }
}
