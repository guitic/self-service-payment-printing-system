using Microsoft.Extensions.Configuration;

namespace PrintAgent.Models;

/// <summary>
/// 打印助手运行配置。对应设计文档 §1.4 助手环境变量清单与任务配置项。
/// 读取优先级：环境变量（PRINT_*） > appsettings.json 的 "Agent" 节点 > 内置默认值。
/// </summary>
public sealed class AgentOptions
{
    /// <summary>后端基址，如 https://print.example.com（不带 /api/v1）。</summary>
    public string BackendUrl { get; set; } = "http://127.0.0.1:8080";

    /// <summary>终端编码，唯一标识（demo 档：TERM-001）。</summary>
    public string TerminalCode { get; set; } = string.Empty;

    /// <summary>助手鉴权 Token，作为 X-Agent-Token 头（demo 档：dev-agent-token）。</summary>
    public string AgentToken { get; set; } = string.Empty;

    /// <summary>空闲轮询间隔（秒）。</summary>
    public int PollIntervalSeconds { get; set; } = 3;

    /// <summary>长轮询 hold 时长（秒），须小于 Nginx proxy_read_timeout 65s。</summary>
    public int LongPollSeconds { get; set; } = 25;

    /// <summary>心跳间隔（秒）。</summary>
    public int HeartbeatSeconds { get; set; } = 30;

    /// <summary>本地瞬时错误重试次数上限。</summary>
    public int MaxRetry { get; set; } = 3;

    /// <summary>离线 spool 目录（下载文件 + 未上报结果持久化）。</summary>
    public string SpoolDir { get; set; } = "spool";

    /// <summary>目标打印机名称，空表示系统默认打印机。</summary>
    public string PrinterName { get; set; } = string.Empty;

    /// <summary>打印后端实现：windows（调系统打印机） / mock（日志模拟出纸）。</summary>
    public string PrintBackend { get; set; } = "mock";

    /// <summary>日志目录（预留，当前日志交控制台/journald）。</summary>
    public string LogDir { get; set; } = "logs";

    /// <summary>助手版本号，随心跳上报。</summary>
    public string AgentVersion { get; set; } = "1.0.0";

    /// <summary>本地打印瞬时错误的退避序列（秒），对应设计文档 PRINT_RETRY_BACKOFF_SECONDS。</summary>
    public int[] RetryBackoffSeconds { get; set; } = new[] { 3, 6, 12 };

    /// <summary>从 IConfiguration 构建配置，并叠加 PRINT_* 环境变量覆盖。</summary>
    public static AgentOptions FromConfiguration(IConfiguration cfg)
    {
        var o = new AgentOptions();
        var s = cfg.GetSection("Agent");

        o.BackendUrl = s["BackendUrl"] ?? o.BackendUrl;
        o.TerminalCode = s["TerminalCode"] ?? o.TerminalCode;
        o.AgentToken = s["AgentToken"] ?? o.AgentToken;
        o.PollIntervalSeconds = ReadInt(s["PollIntervalSeconds"], o.PollIntervalSeconds);
        o.LongPollSeconds = ReadInt(s["LongPollSeconds"], o.LongPollSeconds);
        o.HeartbeatSeconds = ReadInt(s["HeartbeatSeconds"], o.HeartbeatSeconds);
        o.MaxRetry = ReadInt(s["MaxRetry"], o.MaxRetry);
        o.SpoolDir = s["SpoolDir"] ?? o.SpoolDir;
        o.PrinterName = s["PrinterName"] ?? o.PrinterName;
        o.PrintBackend = s["PrintBackend"] ?? o.PrintBackend;
        o.LogDir = s["LogDir"] ?? o.LogDir;
        o.AgentVersion = s["AgentVersion"] ?? o.AgentVersion;

        // —— 环境变量覆盖（设计文档 §1.4，systemd EnvironmentFile 提供）——
        o.BackendUrl = Env(cfg, "PRINT_BACKEND_URL") ?? o.BackendUrl;
        o.TerminalCode = Env(cfg, "PRINT_TERMINAL_CODE") ?? o.TerminalCode;
        o.AgentToken = Env(cfg, "PRINT_AGENT_TOKEN") ?? o.AgentToken;
        o.PollIntervalSeconds = ReadInt(Env(cfg, "PRINT_POLL_INTERVAL_SECONDS"), o.PollIntervalSeconds);
        o.LongPollSeconds = ReadInt(Env(cfg, "PRINT_LONGPOLL_SECONDS"), o.LongPollSeconds);
        o.HeartbeatSeconds = ReadInt(Env(cfg, "PRINT_HEARTBEAT_SECONDS"), o.HeartbeatSeconds);
        o.MaxRetry = ReadInt(Env(cfg, "PRINT_MAX_RETRY"), o.MaxRetry);
        o.SpoolDir = Env(cfg, "PRINT_SPOOL_DIR") ?? o.SpoolDir;
        o.PrinterName = Env(cfg, "PRINT_PRINTER_NAME") ?? o.PrinterName;
        o.PrintBackend = Env(cfg, "PRINT_PRINT_BACKEND") ?? o.PrintBackend;
        o.LogDir = Env(cfg, "PRINT_LOG_DIR") ?? o.LogDir;

        return o;
    }

    /// <summary>配置完整性校验（启动自检，失败仅告警不阻断）。</summary>
    public IReadOnlyList<string> Validate()
    {
        var errors = new List<string>();
        if (string.IsNullOrWhiteSpace(BackendUrl)) errors.Add("BackendUrl 不能为空");
        if (string.IsNullOrWhiteSpace(TerminalCode)) errors.Add("TerminalCode 不能为空");
        if (string.IsNullOrWhiteSpace(AgentToken)) errors.Add("AgentToken 不能为空");
        if (LongPollSeconds <= 0 || LongPollSeconds >= 65) errors.Add("LongPollSeconds 应在 (0,65) 区间（须小于 Nginx 65s）");
        if (HeartbeatSeconds <= 0) errors.Add("HeartbeatSeconds 必须为正");
        return errors;
    }

    private static string? Env(IConfiguration cfg, string key)
    {
        var v = cfg[key];
        return string.IsNullOrWhiteSpace(v) ? null : v;
    }

    private static int ReadInt(string? value, int fallback)
        => int.TryParse(value, out var v) ? v : fallback;
}
