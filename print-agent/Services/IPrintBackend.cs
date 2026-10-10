using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// 打印后端抽象：把「打印调度」与「具体打印实现」解耦。
/// 由配置 <c>PrintBackend</c>（windows / mock）在 DI 时选择实现。
/// </summary>
public interface IPrintBackend
{
    /// <summary>实现名称（windows / mock），用于日志。</summary>
    string Name { get; }

    /// <summary>枚举当前系统打印机及状态，随心跳上报。</summary>
    IReadOnlyList<PrinterStatus> ListPrinters();

    /// <summary>出纸前二次就绪检查（设计文档 §2.6）。</summary>
    Task<bool> IsReadyAsync(string printerName, CancellationToken ct);

    /// <summary>
    /// 执行打印。成功返回；失败抛出 <see cref="PrintBackendException"/> 供上层重试/上报。
    /// </summary>
    /// <param name="filePath">本地已下载的文件绝对路径。</param>
    /// <param name="task">任务参数（份数、单双面、纸张等）。</param>
    Task PrintAsync(string filePath, PrintTask task, CancellationToken ct);
}

/// <summary>打印后端执行失败异常。</summary>
public sealed class PrintBackendException : Exception
{
    /// <summary>是否为不可重试错误（文件损坏 / 格式不支持 / 型号不匹配），见设计文档 §2.5。</summary>
    public bool Permanent { get; }

    public PrintBackendException(string message, bool permanent = false) : base(message)
    {
        Permanent = permanent;
    }

    public PrintBackendException(string message, Exception inner, bool permanent = false) : base(message, inner)
    {
        Permanent = permanent;
    }
}
