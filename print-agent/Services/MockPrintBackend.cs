using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// Mock 打印后端：不接触真实打印机，按页数/份数模拟打印耗时并输出结构化日志，
/// 用于本地联调与 CI 环境（设计文档 demo 档）。
/// </summary>
public sealed class MockPrintBackend : IPrintBackend
{
    private readonly ILogger<MockPrintBackend> _logger;

    public MockPrintBackend(ILogger<MockPrintBackend> logger) => _logger = logger;

    public string Name => "mock";

    public IReadOnlyList<PrinterStatus> ListPrinters()
    {
        return new List<PrinterStatus>
        {
            new() { Name = "Mock Printer (虚拟)", Status = "READY" }
        };
    }

    public Task<bool> IsReadyAsync(string printerName, CancellationToken ct) => Task.FromResult(true);

    public async Task PrintAsync(string filePath, PrintTask task, CancellationToken ct)
    {
        // 模拟出纸耗时：每页 200ms，最少 300ms，最多 5s，避免联调等待过久
        var pages = Math.Max(task.PageCount, 1) * Math.Max(task.Copies, 1);
        var delayMs = Math.Clamp(pages * 200, 300, 5000);

        _logger.LogInformation(
            "[MOCK] 开始出纸 task#{TaskId} order={OrderNo} 目标={Printer} 参数={Color}/{Duplex}/{Paper} x{Copies} 页数={Pages} 预计耗时={Delay}ms",
            task.TaskId, task.OrderNo,
            string.IsNullOrWhiteSpace(task.PrinterName) ? "(默认)" : task.PrinterName,
            task.ColorMode, task.DuplexMode, task.PaperSize, task.Copies, task.PageCount, delayMs);

        await Task.Delay(delayMs, ct);

        _logger.LogInformation(
            "[MOCK] 出纸完成 task#{TaskId} order={OrderNo} file={File} size={Size}B",
            task.TaskId, task.OrderNo, Path.GetFileName(filePath), task.FileSize);
    }
}
