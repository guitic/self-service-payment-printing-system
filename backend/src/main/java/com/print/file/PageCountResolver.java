package com.print.file;

import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.file.convert.FileConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 页数解析器（生产实现）。
 *
 * <ul>
 *   <li>PDF：PDFBox 3.x 精确解析页数；损坏 / 加密 PDF 抛 {@code FILE_PARSE_FAILED}</li>
 *   <li>图片（png/jpg/jpeg/bmp/gif）：固定 1 页</li>
 *   <li>Word / Excel（doc/docx/xls/xlsx）：{@link FileConverter} 可用则转 PDF 后取精确页数；
 *       不可用或转换失败则回退体积估算（约 30KB/页）并打 WARN 降级日志</li>
 * </ul>
 *
 * <p>每个分支均输出「路径 / 页数 / 耗时 ms」日志，便于线上观测与降级排查。
 */
@Slf4j
@Component
public class PageCountResolver {

    /** 体积估算经验值：约 30KB / 页。 */
    private static final long OFFICE_BYTES_PER_PAGE = 30_000L;

    private final PrintProperties props;
    private final List<FileConverter> converters;

    public PageCountResolver(PrintProperties props, List<FileConverter> converters) {
        this.props = props;
        this.converters = converters == null ? List.of() : converters;
    }

    public int resolve(String originalName, Path path) {
        String ext = extOf(originalName);
        long start = System.currentTimeMillis();
        try {
            return switch (ext) {
                case "pdf" -> pdfPages(path, originalName, start);
                case "png", "jpg", "jpeg", "bmp", "gif" -> {
                    log.info("页数解析[图片/固定1页]: file={}, pages=1, cost={}ms",
                            originalName, System.currentTimeMillis() - start);
                    yield 1;
                }
                case "doc", "docx", "xls", "xlsx" -> officePages(originalName, path, start);
                default -> {
                    log.info("页数解析[未知类型/兜底1页]: file={}, ext={}, pages=1, cost={}ms",
                            originalName, ext, System.currentTimeMillis() - start);
                    yield 1;
                }
            };
        } catch (BizException e) {
            // 解析类业务异常需向上抛出（如 PDF 损坏 / 加密）
            throw e;
        } catch (Exception e) {
            log.warn("页数解析失败，按 1 页兜底: {} ({})", originalName, e.getMessage());
            return 1;
        }
    }

    /** PDF 精确页数；损坏或加密 PDF 抛 {@link ResultCode#FILE_PARSE_FAILED}。 */
    private int pdfPages(Path path, String originalName, long start) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            int pages = doc.getNumberOfPages();
            log.info("页数解析[PDF/PDFBox]: file={}, pages={}, cost={}ms",
                    originalName, pages, System.currentTimeMillis() - start);
            return pages;
        } catch (Exception e) {
            log.error("PDF 解析失败: file={}, err={}", originalName, e.getMessage());
            throw new BizException(ResultCode.FILE_PARSE_FAILED.code(),
                    "PDF 解析失败: " + e.getMessage());
        }
    }

    /** Office 文档页数：优先转换取精确页数，失败则降级体积估算。 */
    private int officePages(String originalName, Path path, long start) {
        FileConverter converter = firstAvailableConverter();
        if (converter != null) {
            File pdf = null;
            try {
                pdf = converter.convertToPdf(path.toFile());
                int pages = pdfPages(pdf.toPath(), originalName, start);
                log.info("页数解析[Office/{}转换]: file={}, pages={}, cost={}ms",
                        converter.getClass().getSimpleName(), originalName, pages,
                        System.currentTimeMillis() - start);
                return pages;
            } catch (BizException e) {
                // 转换产物损坏：不直接失败，降级体积估算
                log.warn("Office 转换产物解析失败，降级体积估算: file={}, err={}", originalName, e.getMessage());
            } catch (Exception e) {
                log.warn("Office 转换失败，降级体积估算: file={}, err={}", originalName, e.getMessage());
            } finally {
                if (pdf != null) {
                    try {
                        Files.deleteIfExists(pdf.toPath());
                    } catch (Exception ignored) {
                        // 临时文件清理失败可忽略
                    }
                }
            }
        } else {
            log.warn("未启用文件转换器（print.file.converter={} 或 LibreOffice 不可用），"
                            + "Office 文档页数降级为体积估算: file={}",
                    props.getFile().getConverter(), originalName);
        }
        int estimate = estimateOfficePages(path);
        log.info("页数解析[Office/体积估算]: file={}, pages={}, cost={}ms",
                originalName, estimate, System.currentTimeMillis() - start);
        return estimate;
    }

    private FileConverter firstAvailableConverter() {
        for (FileConverter c : converters) {
            if (c.isAvailable()) {
                return c;
            }
        }
        return null;
    }

    /** 占位估算：约 30KB / 页。 */
    private int estimateOfficePages(Path path) {
        try {
            long size = Files.size(path);
            return (int) Math.max(1, Math.ceil(size / (double) OFFICE_BYTES_PER_PAGE));
        } catch (Exception e) {
            log.warn("体积估算失败，按 1 页兜底: {}", e.getMessage());
            return 1;
        }
    }

    public String extOf(String name) {
        if (name == null || !name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    }
}
