package com.print.task;

/**
 * 打印任务状态字典（CONTRACT.md §2）。
 */
public final class TaskStatus {

    public static final int PENDING = 0;
    public static final int CLAIMED = 1;
    public static final int RUNNING = 2;
    public static final int SUCCESS = 3;
    public static final int FAILED = 4;
    public static final int RETRYING = 5;

    private TaskStatus() {
    }
}
