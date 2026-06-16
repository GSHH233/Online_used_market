package org.example.online_used_marketplace.modules.product.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.product.service.FileService;
import org.example.online_used_marketplace.modules.product.vo.FileUploadVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author Admin
 * @ClassName: FileController
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-17  0:14
 * @Version:
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {
    private final FileService fileService;
    @PostMapping("/upload")
    Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file){
        return Result.success(fileService.upload(file));
    }
}
