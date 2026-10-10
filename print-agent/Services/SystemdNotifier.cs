using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

namespace PrintAgent.Services;

/// <summary>
/// 轻量 systemd 通知器：直接向 $NOTIFY_SOCKET 写入 sd_notify 报文，
/// 使 systemd unit（Type=notify + WatchdogSec）能够感知就绪与存活。
/// 不依赖额外 NuGet 包；非 systemd 环境（如 Windows / 控制台运行）自动降级为无操作。
/// </summary>
public sealed class SystemdNotifier : IDisposable
{
    private readonly Socket? _socket;
    private readonly ILogger<SystemdNotifier> _logger;

    public SystemdNotifier(ILogger<SystemdNotifier> logger)
    {
        _logger = logger;

        var path = Environment.GetEnvironmentVariable("NOTIFY_SOCKET");
        if (string.IsNullOrEmpty(path) || !RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
            return; // 非 systemd 环境，静默降级

        // systemd 抽象命名空间以 '@' 开头，映射为前导 '\0'
        if (path[0] == '@') path = "\0" + path[1..];

        try
        {
            _socket = new Socket(AddressFamily.Unix, SocketType.Dgram, ProtocolType.Unspecified);
            _socket.Connect(new UnixDomainSocketEndPoint(path));
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "连接 systemd NOTIFY_SOCKET 失败，忽略通知");
        }
    }

    public void Ready() => Send("READY=1");
    public void Watchdog() => Send("WATCHDOG=1");
    public void Stopping() => Send("STOPPING=1");

    private void Send(string message)
    {
        if (_socket is null) return;
        try
        {
            _socket.Send(Encoding.UTF8.GetBytes(message + "\n"));
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "发送 systemd 通知失败：{Message}", message);
        }
    }

    public void Dispose() => _socket?.Dispose();
}

/// <summary>
/// systemd 生命周期服务：启动即上报 READY=1，并按 watchdog 周期发送 WATCHDOG=1，
/// 停止时发送 STOPPING=1。watchdog 周期取 30s（&lt; unit 的 WatchdogSec=90s）。
/// </summary>
public sealed class SystemdWatchdogService : IHostedService, IDisposable
{
    private static readonly TimeSpan WatchdogInterval = TimeSpan.FromSeconds(30);

    private readonly SystemdNotifier _notifier;
    private Timer? _timer;

    public SystemdWatchdogService(SystemdNotifier notifier) => _notifier = notifier;

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _notifier.Ready();
        _timer = new Timer(_ => _notifier.Watchdog(), null, WatchdogInterval, WatchdogInterval);
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _notifier.Stopping();
        return Task.CompletedTask;
    }

    public void Dispose()
    {
        _timer?.Dispose();
        _notifier.Dispose();
    }
}
