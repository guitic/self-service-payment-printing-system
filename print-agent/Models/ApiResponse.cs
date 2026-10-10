namespace PrintAgent.Models;

/// <summary>
/// 统一响应体，对应 CONTRACT.md §1.1：{ code, message, data }。
/// code=0 表示成功。
/// </summary>
public sealed class ApiResponse<T>
{
    public int Code { get; set; }
    public string Message { get; set; } = string.Empty;
    public T? Data { get; set; }
}
