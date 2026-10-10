namespace PrintAgent.Services;

/// <summary>
/// 断线重连退避策略（设计文档 §2.3）：
/// 序列 1s→2s→4s→8s→16s→32s→60s(封顶)，叠加 ±20% 抖动；429 尊重 Retry-After。
/// 任一次成功请求调用 <see cref="Reset"/> 立即清零。
/// 注意：该类型非线程安全，每个重连循环应各持有一个实例。
/// </summary>
public sealed class RetryPolicy
{
    private static readonly double[] BackoffSeconds = { 1, 2, 4, 8, 16, 32, 60 };

    private int _attempt;

    /// <summary>当前连续失败次数。</summary>
    public int Attempt => _attempt;

    /// <summary>
    /// 计算下一次重试等待时长。
    /// </summary>
    /// <param name="retryAfter">后端 429 返回的 Retry-After，优先采用且不计入退避序列。</param>
    public TimeSpan NextDelay(TimeSpan? retryAfter = null)
    {
        if (retryAfter.HasValue && retryAfter.Value > TimeSpan.Zero)
        {
            _attempt = 0;
            return retryAfter.Value;
        }

        var idx = Math.Min(_attempt, BackoffSeconds.Length - 1);
        _attempt++;

        var baseSec = BackoffSeconds[idx];
        // ±20% 抖动：避免多终端同时重连打爆后端
        var jitter = 1.0 + (Random.Shared.NextDouble() * 0.4 - 0.2);
        return TimeSpan.FromSeconds(baseSec * jitter);
    }

    /// <summary>请求成功后清零退避计数。</summary>
    public void Reset() => _attempt = 0;
}
