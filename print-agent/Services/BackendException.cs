namespace PrintAgent.Services;

/// <summary>
/// 后端调用异常。区分可重试（5xx / 超时 / 网络不可达 / 429）与不可重试（4xx），
/// 供重连退避策略决策（设计文档 §2.3）。
/// </summary>
public sealed class BackendException : Exception
{
    /// <summary>是否可重试。</summary>
    public bool IsRetryable { get; }

    /// <summary>HTTP 状态码（网络层异常时为 0）。</summary>
    public int StatusCode { get; }

    /// <summary>429 时后端下发的 Retry-After。</summary>
    public TimeSpan? RetryAfter { get; }

    public BackendException(string message, bool isRetryable, int statusCode = 0, TimeSpan? retryAfter = null)
        : base(message)
    {
        IsRetryable = isRetryable;
        StatusCode = statusCode;
        RetryAfter = retryAfter;
    }

    public BackendException(string message, Exception inner, bool isRetryable, int statusCode = 0, TimeSpan? retryAfter = null)
        : base(message, inner)
    {
        IsRetryable = isRetryable;
        StatusCode = statusCode;
        RetryAfter = retryAfter;
    }
}
