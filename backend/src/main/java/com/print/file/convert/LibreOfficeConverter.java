package com.print.file.convert;

import com.print.config.PrintProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 基于 LibreOffice headless 的转换器。
 *
 * <p>通过 {@code soffice --headless --convert-to pdf} 把 Office 文档转换为 PDF，
 * 用于精确页数解析。仅在 {@code print.file.converter=libreoffice} 时注册为 Bean。
 *
 * <p>转换产物落在系统临时目录，转换完成后再拷贝为独立临时文件返回，
 * 中间目录即时清理，避免临时文件堆积。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "print.file.converter", havingValue = "libreoffice")
public class LibreOfficeConverter implements FileConverter {

    /** 探测 --version 的超时（秒）。 */
    private static final long PROBE_TIMEOUT_SECONDS = 15L;

    private final PrintProperties props;

    /** 可用性探测结果缓存（volatile + 双重检查，避免每次转换都起进程探测）。 */
    private volatile Boolean available;

    public LibreOfficeConverter(PrintProperties props) {
        this.props = props;
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            synchronized (this) {
                cached = available;
                if (cached == null) {
                    cached = probe();
                    available = cached;
                }
            }
        }
        return cached;
    }

    private boolean probe() {
        String exe = props.getFile().getLibreofficePath();
        try {
            Process p = new ProcessBuilder(exe, "--version")
                    .redirectErrorStream(true)
                    .start();
            drain(p.getInputStream());
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("LibreOffice 探测超时: {}", exe);
                return false;
            }
            boolean ok = p.exitValue() == 0;
            log.info("LibreOffice 探测{}: {}", ok ? "成功" : "失败(退出码 " + p.exitValue() + ")", exe);
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("LibreOffice 不可用（{}）: {}", exe, e.getMessage());
            return false;
        }
    }

    @Override
    public File convertToPdf(File source) throws IOException {
        String exe = props.getFile().getLibreofficePath();
        int timeoutSeconds = props.getFile().getConvertTimeoutSeconds();
        Path outDir = Files.createTempDirectory("print-convert-");
        long start = System.currentTimeMillis();
        try {
            // 每次转换使用独立的 UserInstallation profile：
            // 否则并发转换会共享同一用户 profile，LibreOffice 拒绝第二实例或锁冲突导致转换失败（降级为体积估算 → 页数不准 → 计价错误）
            Path profileDir = outDir.resolve("lo-profile");
            List<String> cmd = List.of(
                    exe,
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--headless",
                    "--norestore",
                    "--nolockcheck",
                    "--convert-to", "pdf",
                    "--outdir", outDir.toAbsolutePath().toString(),
                    source.getAbsolutePath());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drain(p.getInputStream());
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("LibreOffice 转换超时（" + timeoutSeconds + "s）");
            }
            if (p.exitValue() != 0) {
                throw new IOException("LibreOffice 转换失败，退出码 " + p.exitValue());
            }
            Path produced = findPdf(outDir);
            if (produced == null) {
                throw new IOException("LibreOffice 未生成 PDF 产物");
            }
            // 拷贝为独立临时文件，随即可清理 outDir
            Path target = Files.createTempFile("print-converted-", ".pdf");
            Files.copy(produced, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("LibreOffice 转换完成: {} -> {}，耗时 {}ms",
                    source.getName(), target.getFileName(), System.currentTimeMillis() - start);
            return target.toFile();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LibreOffice 转换被中断", e);
        } finally {
            deleteQuietly(outDir);
        }
    }

    private Path findPdf(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** 异步消费子进程输出，防止管道写满导致子进程阻塞。 */
    private void drain(InputStream in) {
        Thread t = new Thread(() -> {
            try (InputStream is = in) {
                byte[] buf = new byte[4096];
                while (is.read(buf) != -1) {
                    // 丢弃输出，仅用于疏通管道
                }
            } catch (IOException ignored) {
                // 进程结束后流关闭属正常
            }
        }, "lo-drain");
        t.setDaemon(true);
        t.start();
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略清理失败
                }
            });
        } catch (IOException ignored) {
            // 忽略清理失败
        }
    }
}
