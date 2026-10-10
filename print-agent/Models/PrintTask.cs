namespace PrintAgent.Models;

/// <summary>
/// 打印任务对象，严格对应 CONTRACT.md §3.6「任务对象」字段，不增删字段。
/// </summary>
public sealed class PrintTask
{
    public long TaskId { get; set; }
    public string OrderNo { get; set; } = string.Empty;
    public string TerminalCode { get; set; } = string.Empty;
    public string PrinterName { get; set; } = string.Empty;
    public string FileName { get; set; } = string.Empty;
    public long FileSize { get; set; }
    public int PageCount { get; set; }
    public int Copies { get; set; } = 1;
    /// <summary>COLOR / MONO</summary>
    public string ColorMode { get; set; } = "MONO";
    /// <summary>SIMPLEX / DUPLEX</summary>
    public string DuplexMode { get; set; } = "SIMPLEX";
    /// <summary>A4 / A3</summary>
    public string PaperSize { get; set; } = "A4";
    public int RetryCount { get; set; }

    public override string ToString()
        => $"task#{TaskId} order={OrderNo} file={FileName} {ColorMode}/{DuplexMode}/{PaperSize} x{Copies} pages={PageCount}";
}
