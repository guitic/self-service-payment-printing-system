namespace PrintAgent.Models;

/// <summary>心跳请求体，对应 CONTRACT.md §3.6 POST /agent/heartbeat。</summary>
public sealed class HeartbeatRequest
{
    public string TerminalCode { get; set; } = string.Empty;
    public string AgentVersion { get; set; } = "1.0.0";
    public List<PrinterStatus> Printers { get; set; } = new();
}

/// <summary>打印机状态项。</summary>
public sealed class PrinterStatus
{
    public string Name { get; set; } = string.Empty;
    /// <summary>READY / OFFLINE / PAPER_JAM / NO_PAPER / NO_INK / BUSY</summary>
    public string Status { get; set; } = "READY";
}

/// <summary>心跳响应 data。</summary>
public sealed class HeartbeatResponse
{
    public string? ServerTime { get; set; }
    public HeartbeatConfig? Config { get; set; }
}

/// <summary>后端下发的心跳配置（可用于动态覆盖本地参数）。</summary>
public sealed class HeartbeatConfig
{
    public int ClaimTimeoutSeconds { get; set; } = 120;
    public int MaxRetry { get; set; } = 3;
    public int HeartbeatSeconds { get; set; } = 30;
}
