using System.Text.Json.Serialization;

namespace PrintAgent.Models;

/// <summary>
/// 离线 spool 记录：打印已完成（或已判定失败）但上报失败时持久化到本地，
/// 网络恢复后按时间顺序补报（补报幂等，见 CONTRACT.md §5 / 设计文档 §2.3）。
/// </summary>
public sealed class SpoolRecord
{
    public long TaskId { get; set; }
    public string OrderNo { get; set; } = string.Empty;
    /// <summary>SUCCESS / FAILED</summary>
    public string Status { get; set; } = "SUCCESS";
    public string? FailReason { get; set; }
    /// <summary>本地完成时刻（UTC），用于补报排序。</summary>
    public DateTimeOffset FinishedAt { get; set; } = DateTimeOffset.UtcNow;

    [JsonIgnore]
    public string FilePath { get; set; } = string.Empty;

    public ReportRequest ToReport() => new() { Status = Status, FailReason = FailReason };
}
