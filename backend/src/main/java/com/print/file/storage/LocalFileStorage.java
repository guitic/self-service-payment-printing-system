package com.print.file.storage;

import com.print.config.PrintProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地卷存储实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileStorage implements FileStorage {

    private final PrintProperties props;

    private Path root() {
        Path p = Paths.get(props.getFile().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建存储目录: " + p, e);
        }
        return p;
    }

    @Override
    public String store(InputStream in, String relativePath) throws IOException {
        Path target = root().resolve(relativePath).normalize();
        // 防目录穿越
        if (!target.startsWith(root())) {
            throw new IOException("非法的存储路径: " + relativePath);
        }
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return relativePath;
    }

    @Override
    public Path resolve(String storagePath) {
        return root().resolve(storagePath).normalize();
    }

    @Override
    public boolean delete(String storagePath) {
        try {
            return Files.deleteIfExists(resolve(storagePath));
        } catch (IOException e) {
            log.warn("删除文件失败: {}", storagePath, e);
            return false;
        }
    }
}
