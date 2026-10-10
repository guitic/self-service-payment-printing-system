using System.Diagnostics;
using System.Runtime.InteropServices;
using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// 真实打印后端。
///
/// 实现选择说明（任务允许 ProcessStartInfo / System.Drawing.Printing 二选一）：
/// 采用 <see cref="ProcessStartInfo"/> 调用操作系统打印命令，理由：
///   1. 不引入 System.Drawing.Common 等额外 NuGet 依赖，保持仅 Microsoft.Extensions.Hosting；
///   2. Windows 用 Shell 的 "print" / "printto" 动词交给系统打印子系统（Spooler），
///      与用户在资源管理器右键「打印」行为一致，天然支持默认打印机与指定打印机；
///   3. Linux（systemd 部署）用 CUPS 的 `lp` 命令，跨平台一致。
///
/// Windows：Verb=printto 指定打印机（Arguments=打印机名），未指定时 Verb=print 走默认打印机。
/// Linux/macOS：`lp [-d 打印机] 文件`。
/// </summary>
public sealed class WindowsPrintBackend : IPrintBackend
{
    private readonly AgentOptions _options;
    private readonly ILogger<WindowsPrintBackend> _logger;

    public WindowsPrintBackend(AgentOptions options, ILogger<WindowsPrintBackend> logger)
    {
        _options = options;
        _logger = logger;
    }

    public string Name => "windows";

    public IReadOnlyList<PrinterStatus> ListPrinters()
    {
        try
        {
            return RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
                ? ListWindowsPrinters()
                : ListUnixPrinters();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "枚举系统打印机失败，回退为配置打印机");
            return FallbackPrinters();
        }
    }

    public Task<bool> IsReadyAsync(string printerName, CancellationToken ct)
    {
        var printers = ListPrinters();
        if (printers.Count == 0) return Task.FromResult(false);
        if (string.IsNullOrWhiteSpace(printerName))
            return Task.FromResult(true); // 使用默认打印机，无法进一步判定，视为就绪

        var hit = printers.FirstOrDefault(p =>
            string.Equals(p.Name, printerName, StringComparison.OrdinalIgnoreCase));
        return Task.FromResult(hit is not null
            && !string.Equals(hit.Status, "OFFLINE", StringComparison.OrdinalIgnoreCase));
    }

    public async Task PrintAsync(string filePath, PrintTask task, CancellationToken ct)
    {
        if (!File.Exists(filePath))
            throw new PrintBackendException($"待打印文件不存在：{filePath}", permanent: true);

        var printer = string.IsNullOrWhiteSpace(task.PrinterName) ? _options.PrinterName : task.PrinterName;

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            await PrintWindowsAsync(filePath, printer, task, ct);
        else
            await PrintUnixAsync(filePath, printer, task, ct);
    }

    // —— Windows：Shell 打印动词 ——
    private async Task PrintWindowsAsync(string filePath, string printer, PrintTask task, CancellationToken ct)
    {
        var useSpecific = !string.IsNullOrWhiteSpace(printer);
        var psi = new ProcessStartInfo
        {
            FileName = filePath,
            UseShellExecute = true,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            Verb = useSpecific ? "printto" : "print"
        };
        if (useSpecific)
            psi.Arguments = $"\"{printer}\""; // printto 动词接收打印机名作为参数

        _logger.LogInformation(
            "[WIN] 提交打印 task#{TaskId} file={File} 打印机={Printer} 动词={Verb}",
            task.TaskId, Path.GetFileName(filePath), useSpecific ? printer : "(默认)", psi.Verb);

        await RunProcessAsync(psi, task, TimeSpan.FromSeconds(120), ct);
    }

    // —— Linux：CUPS lp ——
    private async Task PrintUnixAsync(string filePath, string printer, PrintTask task, CancellationToken ct)
    {
        var args = new List<string>();
        if (!string.IsNullOrWhiteSpace(printer))
        {
            args.Add("-d");
            args.Add(printer);
        }
        args.Add("-n"); // 份数
        args.Add(Math.Max(task.Copies, 1).ToString());
        if (string.Equals(task.DuplexMode, "DUPLEX", StringComparison.OrdinalIgnoreCase))
        {
            args.Add("-o");
            args.Add("sides=two-sided-long-edge");
        }
        args.Add("-o");
        args.Add($"media={task.PaperSize}");
        args.Add(filePath);

        var psi = new ProcessStartInfo
        {
            FileName = "lp",
            UseShellExecute = false,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            CreateNoWindow = true
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        _logger.LogInformation(
            "[CUPS] 提交打印 task#{TaskId} file={File} 打印机={Printer}",
            task.TaskId, Path.GetFileName(filePath), string.IsNullOrWhiteSpace(printer) ? "(默认)" : printer);

        await RunProcessAsync(psi, task, TimeSpan.FromSeconds(120), ct);
    }

    private async Task RunProcessAsync(ProcessStartInfo psi, PrintTask task, TimeSpan timeout, CancellationToken ct)
    {
        using var process = new Process { StartInfo = psi };
        try
        {
            process.Start();
        }
        catch (Exception ex)
        {
            // 启动失败通常是环境问题（命令缺失 / 权限），归为可重试
            throw new PrintBackendException($"启动打印进程失败：{ex.Message}", ex, permanent: false);
        }

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout);
        try
        {
            await process.WaitForExitAsync(timeoutCts.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            TryKill(process);
            throw new PrintBackendException($"打印进程超时（>{timeout.TotalSeconds}s）", permanent: false);
        }

        var stderr = process.StartInfo.RedirectStandardError ? await process.StandardError.ReadToEndAsync() : string.Empty;

        if (process.ExitCode != 0)
            throw new PrintBackendException($"打印进程退出码 {process.ExitCode}：{stderr.Trim()}", permanent: false);

        _logger.LogInformation(
            "[PRINT] 打印任务已提交/完成 task#{TaskId} order={OrderNo} 耗时未细分", task.TaskId, task.OrderNo);
    }

    private static void TryKill(Process p)
    {
        try { if (!p.HasExited) p.Kill(entireProcessTree: true); } catch { /* ignore */ }
    }

    private IReadOnlyList<PrinterStatus> ListWindowsPrinters()
    {
        var names = new List<string>();
        try
        {
            // wmic 在部分新系统已移除，失败则回退
            var psi = new ProcessStartInfo
            {
                FileName = "wmic",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            psi.ArgumentList.Add("printer");
            psi.ArgumentList.Add("get");
            psi.ArgumentList.Add("name");

            using var p = Process.Start(psi);
            if (p is not null)
            {
                var output = p.StandardOutput.ReadToEnd();
                p.WaitForExit(5000);
                foreach (var raw in output.Split('\n'))
                {
                    var line = raw.Trim();
                    if (line.Length == 0 || line.Equals("Name", StringComparison.OrdinalIgnoreCase)) continue;
                    names.Add(line);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "wmic 枚举打印机失败");
        }

        if (names.Count == 0) return FallbackPrinters();
        return names.Select(n => new PrinterStatus { Name = n, Status = "READY" }).ToList();
    }

    private IReadOnlyList<PrinterStatus> ListUnixPrinters()
    {
        var names = new List<string>();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "lpstat",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            psi.ArgumentList.Add("-a"); // 列出所有打印机及状态

            using var p = Process.Start(psi);
            if (p is not null)
            {
                var output = p.StandardOutput.ReadToEnd();
                p.WaitForExit(5000);
                foreach (var raw in output.Split('\n'))
                {
                    var line = raw.Trim();
                    if (line.Length == 0) continue;
                    var name = line.Split(' ')[0];
                    names.Add($"{name}|READY");
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "lpstat 枚举打印机失败");
        }

        if (names.Count == 0) return FallbackPrinters();
        return names.Select(s =>
        {
            var parts = s.Split('|');
            return new PrinterStatus { Name = parts[0], Status = parts.Length > 1 ? parts[1] : "READY" };
        }).ToList();
    }

    private IReadOnlyList<PrinterStatus> FallbackPrinters()
    {
        var name = string.IsNullOrWhiteSpace(_options.PrinterName) ? "Default Printer" : _options.PrinterName;
        return new List<PrinterStatus> { new() { Name = name, Status = "READY" } };
    }
}
