using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using PrintAgent.Models;

namespace PrintAgent.Services;

/// <summary>
/// 后端 HTTP 客户端。封装统一响应体解析、错误分类（可重试 / 不可重试 / 429 Retry-After），
/// 所有请求携带 X-Agent-Token 头（CONTRACT.md §1 / §3.6）。
/// </summary>
public sealed class BackendClient
{
    private const string BasePath = "/api/v1";

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private readonly HttpClient _http;
    private readonly AgentOptions _options;
    private readonly ILogger<BackendClient> _logger;

    public BackendClient(HttpClient http, AgentOptions options, ILogger<BackendClient> logger)
    {
        _http = http;
        _options = options;
        _logger = logger;
    }

    /// <summary>心跳上报。</summary>
    public async Task<HeartbeatResponse?> HeartbeatAsync(HeartbeatRequest request, CancellationToken ct)
    {
        var body = JsonSerializer.Serialize(request, JsonOpts);
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{BasePath}/agent/heartbeat")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        return await SendAsync<HeartbeatResponse>(req, ct);
    }

    /// <summary>长轮询领取任务，返回 null 表示本次无任务。</summary>
    public async Task<PrintTask?> ClaimTaskAsync(int longPollSeconds, CancellationToken ct)
    {
        var request = new ClaimRequest { TerminalCode = _options.TerminalCode, LongPollSeconds = longPollSeconds };
        var body = JsonSerializer.Serialize(request, JsonOpts);
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{BasePath}/agent/tasks/claim")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        return await SendAsync<PrintTask>(req, ct);
    }

    /// <summary>回传任务结果。</summary>
    public async Task ReportAsync(long taskId, ReportRequest report, CancellationToken ct)
    {
        var body = JsonSerializer.Serialize(report, JsonOpts);
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{BasePath}/agent/tasks/{taskId}/report")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        await SendAsync<JsonElement>(req, ct);
    }

    /// <summary>下载任务文件到指定本地路径。</summary>
    public async Task DownloadToAsync(long taskId, string destPath, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, $"{BasePath}/agent/tasks/{taskId}/file");
        using var resp = await SendForStreamAsync(req, ct);

        var dir = Path.GetDirectoryName(destPath);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

        await using var fs = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true);
        await resp.Content.CopyToAsync(fs, ct);
        await fs.FlushAsync(ct);
    }

    // —— 内部：发送并解析统一响应体 ——
    private async Task<T?> SendAsync<T>(HttpRequestMessage req, CancellationToken ct)
    {
        using var resp = await SendForStreamAsync(req, ct);
        var body = await resp.Content.ReadAsStringAsync(ct);

        ApiResponse<T>? parsed;
        try
        {
            parsed = JsonSerializer.Deserialize<ApiResponse<T>>(body, JsonOpts);
        }
        catch (JsonException ex)
        {
            // 响应非预期 JSON，视为服务端异常，可重试
            throw new BackendException($"响应解析失败：{ex.Message}", ex, isRetryable: true, (int)resp.StatusCode);
        }

        if (parsed is null)
            throw new BackendException("响应为空", isRetryable: true, (int)resp.StatusCode);

        if (parsed.Code != 0)
        {
            // 业务错误码：4xx 语义，不重试
            throw new BackendException($"业务错误 code={parsed.Code} message={parsed.Message}", isRetryable: false, (int)resp.StatusCode);
        }

        return parsed.Data;
    }

    // —— 内部：仅发送并做状态码分类，返回流式响应 ——
    private async Task<HttpResponseMessage> SendForStreamAsync(HttpRequestMessage req, CancellationToken ct)
    {
        // X-Agent-Token 由 HttpClient.DefaultRequestHeaders 统一携带（见 Program.cs），此处仅补 traceId
        if (!req.Headers.Contains("X-Trace-Id"))
            req.Headers.Add("X-Trace-Id", Guid.NewGuid().ToString("N"));

        HttpResponseMessage resp;
        try
        {
            _logger.LogDebug("→ {Method} {Url}", req.Method, req.RequestUri);
            resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // 外部取消（停机），交由上层处理
        }
        catch (OperationCanceledException ex)
        {
            throw new BackendException("请求超时", ex, isRetryable: true);
        }
        catch (HttpRequestException ex)
        {
            throw new BackendException($"网络不可达：{ex.Message}", ex, isRetryable: true);
        }

        var status = (int)resp.StatusCode;

        if (resp.StatusCode == HttpStatusCode.TooManyRequests)
        {
            var retryAfter = ParseRetryAfter(resp);
            resp.Dispose();
            throw new BackendException("后端限流 429", isRetryable: true, status, retryAfter);
        }

        if (status >= 500)
        {
            var text = await SafeReadAsync(resp, ct);
            resp.Dispose();
            throw new BackendException($"服务端错误 {status}：{Truncate(text)}", isRetryable: true, status);
        }

        if (!resp.IsSuccessStatusCode)
        {
            var text = await SafeReadAsync(resp, ct);
            resp.Dispose();
            throw new BackendException($"客户端错误 {status}：{Truncate(text)}", isRetryable: false, status);
        }

        return resp;
    }

    private static TimeSpan? ParseRetryAfter(HttpResponseMessage resp)
    {
        var ra = resp.Headers.RetryAfter;
        if (ra is null) return null;
        if (ra.Delta.HasValue) return ra.Delta.Value;
        if (ra.Date.HasValue) return ra.Date.Value - DateTimeOffset.UtcNow;
        return null;
    }

    private static async Task<string> SafeReadAsync(HttpResponseMessage resp, CancellationToken ct)
    {
        try { return await resp.Content.ReadAsStringAsync(ct); }
        catch { return string.Empty; }
    }

    private static string Truncate(string s, int max = 300)
        => string.IsNullOrEmpty(s) ? string.Empty : (s.Length <= max ? s : s[..max] + "…");
}
