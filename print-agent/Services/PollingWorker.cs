using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// 主轮询工作器：并发运行「心跳循环」与「任务领取循环」，串起完整打印流程：
/// 下载文件 → 就绪检查 → 打印（本地重试）→ 回传（失败落 spool 补报）。
/// 断线按指数退避重连（1→2→4→8→16→32→60s，±20% 抖动），429 尊重 Retry-After，4xx 不重试仅告警。
/// </summary>
public sealed class PollingWorker : BackgroundService
{
    private readonly BackendClient _client;
    private readonly FileDownloader _fileDownloader;
    private readonly IPrintBackend _printBackend;
    private readonly SpoolStore _spool;
    private readonly AgentOptions _options;
    private readonly ILogger<PollingWorker> _logger;

    public PollingWorker(
        BackendClient client,
        FileDownloader fileDownloader,
        IPrintBackend printBackend,
        SpoolStore spool,
        AgentOptions options,
        ILogger<PollingWorker> logger)
    {
        _client = client;
        _fileDownloader = fileDownloader;
        _printBackend = printBackend;
        _spool = spool;
        _options = options;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation(
            "打印助手启动 terminal={Terminal} backend={Backend} 打印后端={Impl} version={Version}",
            _options.TerminalCode, _options.BackendUrl, _printBackend.Name, _options.AgentVersion);

        // —— 启动自检（失败不阻断启动，仅告警）——
        var errors = _options.Validate();
        if (errors.Count > 0)
            _logger.LogError("配置校验未通过（继续启动）：{Errors}", string.Join("；", errors));

        _spool.CleanupTemps();
        _logger.LogInformation("spool 目录：{Dir}", _spool.RootPath);

        var heartbeat = HeartbeatLoopAsync(stoppingToken);
        var claim = ClaimLoopAsync(stoppingToken);
        await Task.WhenAll(heartbeat, claim);

        _logger.LogInformation("打印助手已停止");
    }

    // ——————————————— 心跳循环 ———————————————
    private async Task HeartbeatLoopAsync(CancellationToken ct)
    {
        var policy = new RetryPolicy();
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await SendHeartbeatAsync(ct);
                policy.Reset();
                await Task.Delay(TimeSpan.FromSeconds(_options.HeartbeatSeconds), ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (BackendException ex) when (ex.IsRetryable)
            {
                var delay = policy.NextDelay(ex.RetryAfter);
                _logger.LogWarning("心跳失败（可重试，第 {Attempt} 次）：{Msg}，{Delay:F1}s 后重试",
                    policy.Attempt, ex.Message, delay.TotalSeconds);
                await DelaySafe(delay, ct);
            }
            catch (BackendException ex)
            {
                _logger.LogError("心跳失败（不可重试，需人工介入，如 Token 失效）：{Msg}", ex.Message);
                await DelaySafe(TimeSpan.FromSeconds(_options.HeartbeatSeconds), ct);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "心跳循环异常，稍后继续");
                await DelaySafe(TimeSpan.FromSeconds(_options.HeartbeatSeconds), ct);
            }
        }
    }

    private async Task SendHeartbeatAsync(CancellationToken ct)
    {
        var printers = _printBackend.ListPrinters().ToList();
        var request = new HeartbeatRequest
        {
            TerminalCode = _options.TerminalCode,
            AgentVersion = _options.AgentVersion,
            Printers = printers
        };

        var resp = await _client.HeartbeatAsync(request, ct);
        _logger.LogDebug("心跳成功 terminal={Terminal} 打印机={Count} serverTime={ServerTime}",
            _options.TerminalCode, printers.Count, resp?.ServerTime ?? "-");
    }

    // ——————————————— 领取循环 ———————————————
    private async Task ClaimLoopAsync(CancellationToken ct)
    {
        var policy = new RetryPolicy();
        while (!ct.IsCancellationRequested)
        {
            // 领取前先补报离线积压结果
            await FlushSpoolAsync(ct);

            try
            {
                var task = await _client.ClaimTaskAsync(_options.LongPollSeconds, ct);
                policy.Reset();

                if (task is null)
                {
                    await DelaySafe(TimeSpan.FromSeconds(_options.PollIntervalSeconds), ct);
                    continue;
                }

                await ProcessTaskAsync(task, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (BackendException ex) when (ex.IsRetryable)
            {
                var delay = policy.NextDelay(ex.RetryAfter);
                _logger.LogWarning("领取任务失败（可重试）：{Msg}，{Delay:F1}s 后重试", ex.Message, delay.TotalSeconds);
                await DelaySafe(delay, ct);
            }
            catch (BackendException ex)
            {
                _logger.LogError("领取任务失败（不可重试，需人工介入）：{Msg}", ex.Message);
                await DelaySafe(TimeSpan.FromSeconds(_options.PollIntervalSeconds), ct);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "领取循环异常，稍后继续");
                await DelaySafe(TimeSpan.FromSeconds(_options.PollIntervalSeconds), ct);
            }
        }
    }

    // ——————————————— 单任务处理 ———————————————
    private async Task ProcessTaskAsync(PrintTask task, CancellationToken ct)
    {
        _logger.LogInformation("领取到任务 {Task}", task);
        string? localPath = null;
        try
        {
            // 1. 下载文件
            localPath = await _fileDownloader.DownloadAsync(task, ct);

            // 2. 出纸前二次就绪检查
            var printer = string.IsNullOrWhiteSpace(task.PrinterName) ? _options.PrinterName : task.PrinterName;
            if (!await _printBackend.IsReadyAsync(printer, ct))
            {
                _logger.LogWarning("打印机未就绪，放弃本次打印 task#{TaskId} printer={Printer}", task.TaskId, printer);
                await ReportWithSpoolFallbackAsync(task, ReportRequest.Failed("打印机未就绪"), ct);
                return;
            }

            // 3. 打印（含本地瞬时错误重试）
            await PrintWithLocalRetryAsync(localPath, task, ct);

            // 4. 回传成功
            await ReportWithSpoolFallbackAsync(task, ReportRequest.Success(), ct);
        }
        catch (PrintBackendException ex)
        {
            _logger.LogError("打印失败 task#{TaskId} permanent={Permanent}：{Msg}", task.TaskId, ex.Permanent, ex.Message);
            await ReportWithSpoolFallbackAsync(task, ReportRequest.Failed(ex.Message), ct);
        }
        catch (BackendException ex) when (ex.IsRetryable)
        {
            // 下载失败等后端不可用：不消耗本地重试，交由后端 claimTimeout 回收后重派
            _logger.LogWarning("处理任务 task#{TaskId} 时后端不可用：{Msg}；等待后端超时回收", task.TaskId, ex.Message);
        }
        catch (BackendException ex)
        {
            _logger.LogError("处理任务 task#{TaskId} 失败（不可重试）：{Msg}", task.TaskId, ex.Message);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            _logger.LogWarning("停机中断任务 task#{TaskId} 处理，将由后端超时回收", task.TaskId);
            throw;
        }
        finally
        {
            if (localPath is not null) _fileDownloader.TryDelete(localPath);
        }
    }

    /// <summary>本地瞬时错误重试：退避 3s/6s/12s，上限 MaxRetry 次；不可重试错误直接抛出。</summary>
    private async Task PrintWithLocalRetryAsync(string localPath, PrintTask task, CancellationToken ct)
    {
        var backoff = _options.RetryBackoffSeconds is { Length: > 0 }
            ? _options.RetryBackoffSeconds
            : new[] { 3, 6, 12 };
        var maxRetries = Math.Max(_options.MaxRetry, 0);

        for (var attempt = 0; attempt <= maxRetries; attempt++)
        {
            try
            {
                await _printBackend.PrintAsync(localPath, task, ct);
                _logger.LogInformation("打印完成 task#{TaskId} order={OrderNo}", task.TaskId, task.OrderNo);
                return;
            }
            catch (PrintBackendException ex) when (ex.Permanent)
            {
                throw; // 文件损坏 / 格式不支持 / 型号不匹配：不占用重试
            }
            catch (PrintBackendException ex)
            {
                if (attempt == maxRetries)
                    throw new PrintBackendException($"本地重试 {maxRetries} 次仍失败：{ex.Message}", ex);

                var wait = attempt < backoff.Length ? backoff[attempt] : backoff[^1];
                _logger.LogWarning("打印瞬时失败 task#{TaskId}（第 {Attempt}/{Max} 次）：{Msg}，{Wait}s 后重试",
                    task.TaskId, attempt + 1, maxRetries, ex.Message, wait);
                await DelaySafe(TimeSpan.FromSeconds(wait), ct);
            }
        }
    }

    // ——————————————— 回传与 spool ———————————————
    private async Task ReportWithSpoolFallbackAsync(PrintTask task, ReportRequest report, CancellationToken ct)
    {
        try
        {
            await _client.ReportAsync(task.TaskId, report, ct);
            _logger.LogInformation("回传成功 task#{TaskId} status={Status}", task.TaskId, report.Status);
        }
        catch (BackendException ex) when (ex.IsRetryable)
        {
            _logger.LogWarning("回传失败（可重试）task#{TaskId}：{Msg}，写入离线 spool 待补报", task.TaskId, ex.Message);
            await SpoolAsync(task, report);
        }
        catch (BackendException ex)
        {
            _logger.LogError("回传失败（不可重试，告警）task#{TaskId} status={Status}：{Msg}",
                task.TaskId, report.Status, ex.Message);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // 停机中：先持久化再退出，确保不丢结果
            await SpoolAsync(task, report);
            throw;
        }
    }

    private Task SpoolAsync(PrintTask task, ReportRequest report)
    {
        var record = new SpoolRecord
        {
            TaskId = task.TaskId,
            OrderNo = task.OrderNo,
            Status = report.Status,
            FailReason = report.FailReason,
            FinishedAt = DateTimeOffset.UtcNow
        };
        // spool 写入必须完成，不随停机取消
        return _spool.SaveAsync(record, CancellationToken.None);
    }

    private async Task FlushSpoolAsync(CancellationToken ct)
    {
        var pending = await _spool.ListPendingAsync(ct);
        if (pending.Count == 0) return;

        _logger.LogInformation("发现 {Count} 条离线待补报记录，尝试补报", pending.Count);
        foreach (var rec in pending)
        {
            if (ct.IsCancellationRequested) return;
            try
            {
                await _client.ReportAsync(rec.TaskId, rec.ToReport(), ct);
                await _spool.RemoveAsync(rec, ct);
                _logger.LogInformation("补报成功 task#{TaskId} status={Status}", rec.TaskId, rec.Status);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (BackendException ex) when (ex.IsRetryable)
            {
                _logger.LogWarning("补报失败（后端不可用），保留 spool 下次再试：task#{TaskId} {Msg}", rec.TaskId, ex.Message);
                return; // 停止本轮，避免无谓循环
            }
            catch (BackendException ex)
            {
                _logger.LogError("补报失败（不可重试），丢弃该记录：task#{TaskId} {Msg}", rec.TaskId, ex.Message);
                await _spool.RemoveAsync(rec, ct);
            }
        }
    }

    private static async Task DelaySafe(TimeSpan delay, CancellationToken ct)
    {
        try
        {
            await Task.Delay(delay, ct);
        }
        catch (OperationCanceledException)
        {
            // 停机，交由外层循环退出
        }
    }
}
