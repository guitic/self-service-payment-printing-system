package com.print.file;

import com.print.common.BizException;
import com.print.common.ResultCode;
import com.print.config.PrintProperties;
import com.print.file.entity.PrintFile;
import com.print.file.mapper.PrintFileMapper;
import com.print.file.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件服务：上传校验（白名单 + 大小 + 魔数）、页数解析、鉴权下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PrintFileMapper fileMapper;
    private final FileStorage storage;
    private final PageCountResolver pageCountResolver;
    private final PrintProperties props;

    public record FileVO(Long id, String originalName, String contentType, long sizeBytes, int pageCount) {
        public static FileVO of(PrintFile f) {
            return new FileVO(f.getId(), f.getOriginalName(), f.getContentType(),
                    f.getSizeBytes() == null ? 0 : f.getSizeBytes(),
                    f.getPageCount() == null ? 0 : f.getPageCount());
        }
    }

    /** 上传文件并解析页数。 */
    public FileVO upload(MultipartFile mf, Long userId) {
        if (mf == null || mf.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "文件不能为空");
        }
        String originalName = StringUtils.cleanPath(
                Objects.requireNonNullElse(mf.getOriginalFilename(), "unnamed"));
        String ext = pageCountResolver.extOf(originalName);

        if (!props.getFile().getAllowedExt().contains(ext)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED);
        }
        long maxBytes = props.getFile().getMaxSizeMb() * 1024 * 1024;
        if (mf.getSize() > maxBytes) {
            throw new BizException(ResultCode.FILE_TOO_LARGE);
        }

        byte[] data;
        try {
            data = mf.getBytes();
        } catch (IOException e) {
            throw new BizException(ResultCode.FILE_PARSE_FAILED);
        }
        // 魔数校验：防止扩展名伪装（TC-FILE-05）
        if (!magicOk(ext, data)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED.code(), "文件内容与扩展名不符，已拒绝");
        }

        String relative = LocalDate.now().format(DAY) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            storage.store(new ByteArrayInputStream(data), relative);
        } catch (IOException e) {
            log.error("文件写入失败", e);
            throw new BizException(ResultCode.FILE_PARSE_FAILED);
        }

        int pageCount = pageCountResolver.resolve(originalName, storage.resolve(relative));

        LocalDateTime now = LocalDateTime.now();
        PrintFile f = new PrintFile();
        f.setUserId(userId);
        f.setOriginalName(originalName);
        f.setStoragePath(relative);
        f.setContentType(mf.getContentType());
        f.setSizeBytes(mf.getSize());
        f.setPageCount(pageCount);
        f.setStatus(0);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        fileMapper.insert(f);

        log.info("文件上传成功: id={}, name={}, size={}, pages={}", f.getId(), originalName, mf.getSize(), pageCount);
        return FileVO.of(f);
    }

    /** 取文件并校验归属，防越权（TC-FILE-07/08）。 */
    public PrintFile requireOwned(Long id, Long userId) {
        PrintFile f = fileMapper.selectById(id);
        if (f == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (userId == null || !Objects.equals(f.getUserId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        return f;
    }

    /** 取文件元数据（不做归属校验，供内部模块使用）。 */
    public PrintFile findMeta(Long id) {
        if (id == null) {
            return null;
        }
        return fileMapper.selectById(id);
    }

    /** 按存储路径查文件（助手下载任务文件时使用）。 */
    public PrintFile findMetaByStoragePath(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return null;
        }
        return fileMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PrintFile>()
                .eq(PrintFile::getStoragePath, storagePath)
                .last("LIMIT 1"));
    }

    public Resource loadResource(PrintFile f) {
        return new FileSystemResource(storage.resolve(f.getStoragePath()));
    }

    private boolean magicOk(String ext, byte[] d) {
        if (d.length < 4) {
            return false;
        }
        return switch (ext) {
            case "pdf" -> startsWith(d, "%PDF");
            case "png" -> (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
            case "jpg", "jpeg" -> (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
            case "gif" -> d[0] == 'G' && d[1] == 'I' && d[2] == 'F';
            case "bmp" -> d[0] == 'B' && d[1] == 'M';
            case "docx", "xlsx" -> d[0] == 'P' && d[1] == 'K';
            case "doc", "xls" -> (d[0] & 0xFF) == 0xD0 && (d[1] & 0xFF) == 0xCF;
            default -> true;
        };
    }

    private boolean startsWith(byte[] data, String magic) {
        byte[] m = magic.getBytes(StandardCharsets.US_ASCII);
        if (data.length < m.length) {
            return false;
        }
        for (int i = 0; i < m.length; i++) {
            if (data[i] != m[i]) {
                return false;
            }
        }
        return true;
    }
}
