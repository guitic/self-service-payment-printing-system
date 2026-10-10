namespace PrintAgent.Models;

/// <summary>领取任务请求体，对应 CONTRACT.md §3.6 POST /agent/tasks/claim。</summary>
public sealed class ClaimRequest
{
    public string TerminalCode { get; set; } = string.Empty;
    public int LongPollSeconds { get; set; } = 25;
}

/// <summary>结果回传请求体，对应 CONTRACT.md §3.6 POST /agent/tasks/{id}/report。</summary>
public sealed class ReportRequest
{
    /// <summary>SUCCESS / FAILED</summary>
    public string Status { get; set; } = "SUCCESS";
    public string? FailReason { get; set; }

    public static ReportRequest Success() => new() { Status = "SUCCESS" };

    public static ReportRequest Failed(string reason) => new() { Status = "FAILED", FailReason = reason };
}
