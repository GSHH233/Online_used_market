package org.example.online_used_marketplace.modules.product.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.config.MinioProperties;
import org.example.online_used_marketplace.modules.product.service.FileService;
import org.example.online_used_marketplace.modules.product.vo.FileUploadVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * @author Admin
 * @ClassName: FileServiceImpl
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-16  23:28
 * @Version:
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl implements FileService {
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".png", "image/png",
            ".gif", "image/gif", ".webp", "image/webp", ".bmp", "image/bmp");
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private volatile boolean bucketInitialized;

    @Override
    public FileUploadVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传图片不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot > 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
        String expectedType = IMAGE_TYPES.get(ext);
        if (expectedType == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持JPG、JPEG、PNG、GIF、WEBP、BMP图片");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片大小不能超过5MB");
        }

        byte[] content;
        try (InputStream input = file.getInputStream()) {
            // Bound the actual bytes, not just the multipart-reported size.
            content = input.readNBytes(MAX_IMAGE_BYTES + 1);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法读取上传图片");
        }
        if (content.length == 0 || content.length > MAX_IMAGE_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片不能为空且大小不能超过5MB");
        }
        // Do not pass the untrusted filename or Content-Type to Tika.
        String contentType = new Tika().detect(content);
        if (!expectedType.equals(contentType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片实际类型与扩展名不匹配");
        }

        String bucket = minioProperties.getBucketName();
        ensureBucket(bucket);
        String objectName = UUID.randomUUID() + ext;
        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder().object(objectName)
                    .stream(inputStream, content.length, -1).bucket(bucket).contentType(contentType).build());
        } catch (Exception e) {
            log.error("MinIO文件上传失败, bucket={}, objectName={}, size={}, contentType={}, reason={}",
                    bucket, objectName, content.length, contentType, e.getMessage(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件上传失败");
        }
        FileUploadVO vo=new FileUploadVO();
        String url=minioProperties.getEndpoint()+"/"+bucket+"/"+objectName;
        vo.setUrl(url);
        vo.setObjectName(objectName);
        return vo;
    }

    private void ensureBucket(String bucket) {
        if (bucketInitialized) {
            return;
        }
        synchronized (this) {
            if (bucketInitialized) {
                return;
            }
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                bucketInitialized = true;
            } catch (Exception e) {
                log.error("MinIO bucket检查/创建失败, bucket={}, endpoint={}",
                        bucket, minioProperties.getEndpoint(), e);
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "bucket检查/创建失败");
            }
        }
    }
}
