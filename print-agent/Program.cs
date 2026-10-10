using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PrintAgent.Models;
using PrintAgent.Services;

// 打印助手入口：.NET 8 Worker Service
// 配置来源：appsettings.json 的 "Agent" 节点 + PRINT_* 环境变量覆盖（见 AgentOptions.FromConfiguration）
var builder = Host.CreateDefaultBuilder(args);

builder.ConfigureServices((context, services) =>
{
    var options = AgentOptions.FromConfiguration(context.Configuration);
    services.AddSingleton(options);

    // —— 后端 HTTP 客户端 ——
    // 超时须大于长轮询 25s，取 70s（与 Nginx /api proxy_read_timeout 65s 匹配）
    services.AddSingleton(sp =>
    {
        var http = new HttpClient
        {
            BaseAddress = new Uri(options.BackendUrl.TrimEnd('/') + "/"),
            Timeout = TimeSpan.FromSeconds(70)
        };
        http.DefaultRequestHeaders.Add("X-Agent-Token", options.AgentToken);
        http.DefaultRequestHeaders.Add("Accept", "application/json");
        return new BackendClient(http, options, sp.GetRequiredService<ILogger<BackendClient>>());
    });

    // —— 本地 spool 与文件下载 ——
    services.AddSingleton<SpoolStore>();
    services.AddSingleton<FileDownloader>();

    // —— 打印后端实现选择（windows / mock）——
    if (string.Equals(options.PrintBackend, "windows", StringComparison.OrdinalIgnoreCase))
        services.AddSingleton<IPrintBackend, WindowsPrintBackend>();
    else
        services.AddSingleton<IPrintBackend, MockPrintBackend>();

    // —— systemd 通知（非 systemd 环境自动降级为无操作）——
    services.AddSingleton<SystemdNotifier>();
    services.AddHostedService<SystemdWatchdogService>();

    // —— 主轮询工作器 ——
    services.AddHostedService<PollingWorker>();
});

var host = builder.Build();
await host.RunAsync();
