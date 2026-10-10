using System.Text.Json;
using System.Text.Json.Serialization;
using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// 离线 spool 存储（设计文档 §2.3）：断网期间把已完成打印但未上报的结果落本地 JSON，
/// 网络恢复后按时间顺序补报。写入采用「临时文件 + 原子 rename」，避免半写文件被误读。
/// </summary>
public sealed class SpoolStore
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private readonly string _dir;
    private readonly ILogger<SpoolStore> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public SpoolStore(AgentOptions options, ILogger<SpoolStore> logger)
    {
        _logger = logger;
        _dir = Path.GetFullPath(options.SpoolDir);
        Directory.CreateDirectory(_dir);
    }

    /// <summary>spool 目录绝对路径。</summary>
    public string RootPath => _dir;

    /// <summary>原子写入一条待补报记录。</summary>
    public async Task SaveAsync(SpoolRecord record, CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct);
        try
        {
            var name = $"{record.FinishedAt:yyyyMMddHHmmssfff}-{record.TaskId}-{Guid.NewGuid():N}.json";
            var finalPath = Path.Combine(_dir, name);
            var tmpPath = finalPath + ".tmp";

            var json = JsonSerializer.Serialize(record, JsonOpts);
            await File.WriteAllTextAsync(tmpPath, json, ct);

            // 同目录内 rename 在 POSIX 上原子；Windows 同名目标不存在亦安全
            File.Move(tmpPath, finalPath, overwrite: true);
            record.FilePath = finalPath;

            _logger.LogWarning("已写入离线 spool（待网络恢复补报）：task#{TaskId} status={Status} file={File}",
                record.TaskId, record.Status, Path.GetFileName(finalPath));
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>列出待补报记录，按完成时间升序（先完成先补报）。</summary>
    public async Task<IReadOnlyList<SpoolRecord>> ListPendingAsync(CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct);
        try
        {
            var list = new List<SpoolRecord>();
            foreach (var file in Directory.EnumerateFiles(_dir, "*.json"))
            {
                try
                {
                    var json = await File.ReadAllTextAsync(file, ct);
                    var rec = JsonSerializer.Deserialize<SpoolRecord>(json, JsonOpts);
                    if (rec is null) continue;
                    rec.FilePath = file;
                    list.Add(rec);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "解析 spool 文件失败，跳过：{File}", file);
                }
            }
            return list.OrderBy(r => r.FinishedAt).ToList();
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>补报成功后删除对应 spool 文件。</summary>
    public Task RemoveAsync(SpoolRecord record, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(record.FilePath)) return Task.CompletedTask;
        try
        {
            if (File.Exists(record.FilePath)) File.Delete(record.FilePath);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "删除 spool 文件失败：{File}", record.FilePath);
        }
        return Task.CompletedTask;
    }

    /// <summary>清理遗留的 .tmp 半成品文件（启动自检时调用）。</summary>
    public void CleanupTemps()
    {
        try
        {
            foreach (var tmp in Directory.EnumerateFiles(_dir, "*.tmp"))
            {
                try { File.Delete(tmp); } catch { /* ignore */ }
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "清理 spool 临时文件失败");
        }
    }
}
