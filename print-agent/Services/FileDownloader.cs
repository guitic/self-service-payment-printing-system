using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// 任务文件下载器：把后端文件流落到本地 spool/files 目录，打印后清理。
/// 文件名做路径穿越防护（仅取文件名部分）。
/// </summary>
public sealed class FileDownloader
{
    private readonly BackendClient _client;
    private readonly AgentOptions _options;
    private readonly ILogger<FileDownloader> _logger;
    private readonly string _filesDir;

    public FileDownloader(BackendClient client, AgentOptions options, ILogger<FileDownloader> logger)
    {
        _client = client;
        _options = options;
        _logger = logger;
        _filesDir = Path.Combine(Path.GetFullPath(options.SpoolDir), "files");
        Directory.CreateDirectory(_filesDir);
    }

    /// <summary>根据任务构造本地文件路径（task-{id}-{安全文件名}）。</summary>
    public string BuildLocalPath(PrintTask task)
    {
        var safeName = Sanitize(task.FileName);
        if (string.IsNullOrEmpty(safeName)) safeName = $"task-{task.TaskId}.bin";
        return Path.Combine(_filesDir, $"task-{task.TaskId}-{safeName}");
    }

    /// <summary>下载任务文件，返回本地绝对路径。</summary>
    public async Task<string> DownloadAsync(PrintTask task, CancellationToken ct)
    {
        var path = BuildLocalPath(task);
        _logger.LogInformation("开始下载任务文件 task#{TaskId} file={File} size={Size}B",
            task.TaskId, task.FileName, task.FileSize);

        await _client.DownloadToAsync(task.TaskId, path, ct);

        var actual = new FileInfo(path).Length;
        _logger.LogInformation("文件下载完成 task#{TaskId} → {Path}（{Actual}B）", task.TaskId, path, actual);
        return path;
    }

    /// <summary>打印结束（成功/失败）后清理本地文件。</summary>
    public void TryDelete(string path)
    {
        try
        {
            if (!string.IsNullOrEmpty(path) && File.Exists(path)) File.Delete(path);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "清理本地文件失败：{Path}", path);
        }
    }

    private static string Sanitize(string fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName)) return string.Empty;
        var name = Path.GetFileName(fileName); // 去除目录部分，防路径穿越
        foreach (var c in Path.GetInvalidFileNameChars())
            name = name.Replace(c, '_');
        return name;
    }
}
