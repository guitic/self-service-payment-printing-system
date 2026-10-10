package com.print.file.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文件存储抽象（ADR-004）：默认本地卷，规模化可切换 MinIO。
 */
public interface FileStorage {

    /**
     * 保存文件。
     *
     * @param in           输入流
     * @param relativePath 相对路径，如 {@code 20261010/uuid.pdf}
     * @return 实际写入的相对路径
     */
    String store(InputStream in, String relativePath) throws IOException;

    /** 解析为本地绝对路径。 */
    Path resolve(String storagePath);

    boolean delete(String storagePath);
}
