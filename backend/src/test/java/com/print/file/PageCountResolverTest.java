package com.print.file;

import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.file.convert.FileConverter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 页数解析器纯单元测试（不启 Spring）。
 *
 * <p>覆盖：PDF 精确页数、图片固定 1 页、损坏文件拒绝、转换器缺失时的降级路径、
 * 转换器可用时走转换取精确页数。
 */
class PageCountResolverTest {

    @TempDir
    Path tmp;

    /** 无任何转换器（模拟 converter=none / LibreOffice 不可用）。 */
    private PageCountResolver resolverWithoutConverter() {
        return new PageCountResolver(new PrintProperties(), List.of());
    }

    private Path writePdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            Path f = tmp.resolve("gen-" + pages + "p.pdf");
            doc.save(f.toFile());
            return f;
        }
    }

    /** 最小 PNG：8 字节签名 + IHDR 块。 */
    private byte[] minimalPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13,
                0x49, 0x48, 0x44, 0x52,
                0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
                0x1F, 0x15, (byte) 0xC4, (byte) 0x89
        };
    }

    @Test
    @DisplayName("PDF 单页 -> 解析为 1 页")
    void pdfOnePage() throws IOException {
        assertThat(resolverWithoutConverter().resolve("a.pdf", writePdf(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("PDF 五页 -> 精确解析为 5 页")
    void pdfFivePages() throws IOException {
        assertThat(resolverWithoutConverter().resolve("b.pdf", writePdf(5))).isEqualTo(5);
    }

    @Test
    @DisplayName("图片（PNG）-> 固定 1 页")
    void imageIsOnePage() throws IOException {
        Path png = tmp.resolve("img.png");
        Files.write(png, minimalPng());
        assertThat(resolverWithoutConverter().resolve("img.png", png)).isEqualTo(1);
    }

    @Test
    @DisplayName("损坏文件（非 PDF）-> 抛 FILE_PARSE_FAILED")
    void corruptFileThrows() throws IOException {
        Path bad = tmp.resolve("bad.pdf");
        Files.write(bad, "this is definitely not a pdf file".getBytes());

        assertThatThrownBy(() -> resolverWithoutConverter().resolve("bad.pdf", bad))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ResultCode.FILE_PARSE_FAILED.code()));
    }

    @Test
    @DisplayName("转换器不可用 -> Office 降级体积估算，不抛异常且页数 ≥1")
    void officeFallbackWithoutConverter() throws IOException {
        Path docx = tmp.resolve("big.docx");
        Files.write(docx, new byte[100_000]); // 约 100KB -> 估算约 4 页

        int pages = resolverWithoutConverter().resolve("big.docx", docx);
        assertThat(pages).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("转换器可用 -> Office 走转换取精确页数（3 页）")
    void officeUsesConverterWhenAvailable() throws IOException {
        Path docx = tmp.resolve("c.docx");
        Files.write(docx, new byte[16]);
        Path converted = writePdf(3);

        FileConverter fake = new FileConverter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public File convertToPdf(File source) {
                return converted.toFile();
            }
        };
        PageCountResolver resolver = new PageCountResolver(new PrintProperties(), List.of(fake));

        assertThat(resolver.resolve("c.docx", docx)).isEqualTo(3);
    }

    @Test
    @DisplayName("转换器报不可用 -> 回落到体积估算（不抛异常）")
    void unavailableConverterFallsBack() throws IOException {
        Path docx = tmp.resolve("d.docx");
        Files.write(docx, new byte[60_000]);

        FileConverter unavailable = new FileConverter() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public File convertToPdf(File source) {
                throw new AssertionError("不可用时不应被调用");
            }
        };
        PageCountResolver resolver = new PageCountResolver(new PrintProperties(), List.of(unavailable));

        assertThat(resolver.resolve("d.docx", docx)).isGreaterThanOrEqualTo(1);
    }
}
