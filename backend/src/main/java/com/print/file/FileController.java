package com.print.file;

import com.print.common.Result;
import com.print.config.UserContext;
import com.print.file.entity.PrintFile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 文件接口（CONTRACT.md §3.2）。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping
    public Result<FileService.FileVO> upload(@RequestPart("file") MultipartFile file) {
        return Result.ok(fileService.upload(file, UserContext.userId()));
    }

    @GetMapping("/{id}")
    public Result<FileService.FileVO> meta(@PathVariable Long id) {
        return Result.ok(FileService.FileVO.of(fileService.requireOwned(id, UserContext.userId())));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        PrintFile f = fileService.requireOwned(id, UserContext.userId());
        Resource resource = fileService.loadResource(f);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(f.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
