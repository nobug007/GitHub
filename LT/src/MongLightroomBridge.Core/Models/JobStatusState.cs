namespace MongLightroomBridge.Core.Models;

/// <summary>Lifecycle of a job, mirrored 1:1 by the "status" field the Lua plugin writes back.</summary>
public enum JobStatusState
{
    Pending,
    Running,
    Completed,
    Failed,
}
