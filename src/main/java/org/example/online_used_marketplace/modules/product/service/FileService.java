package org.example.online_used_marketplace.modules.product.service;

import org.example.online_used_marketplace.modules.product.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author Admin
 * @ClassName: FileService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-16  23:27
 * @Version:
 */
public interface FileService {
    FileUploadVO upload(MultipartFile file);
}
