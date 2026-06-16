package org.example.online_used_marketplace.modules.product.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.config.MinioProperties;
import org.example.online_used_marketplace.modules.product.service.FileService;
import org.example.online_used_marketplace.modules.product.vo.FileUploadVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
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
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public FileUploadVO upload(MultipartFile file) {
        if (file==null||file.isEmpty()){
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"上传文件失败");
        }
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (contentType==null||contentType.isBlank()){
            contentType="application/octet-stream";
        }
        String ext = "";
        if (filename==null){
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"文件名不能为空");
        }
        String bucket = minioProperties.getBucketName();

        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())){
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.error("MinIO bucket检查/创建失败, bucket={}, endpoint={}, reason={}",
                    bucket, minioProperties.getEndpoint(), e.getMessage(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "bucket检查/创建失败");
        }
        int i = filename.lastIndexOf(".");
        if (i>=0&&i<filename.length()-1){
            ext=filename.substring(i);
        }
        if (i==0){
            ext="";
        }

        String objectName;
        UUID uuid = UUID.randomUUID();
        objectName= String.valueOf(uuid)+ext;
        try(InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder().object(objectName).stream(inputStream, file.getSize(), -1).bucket(bucket).contentType(contentType).build());
        } catch (Exception e) {
            log.error("MinIO文件上传失败, bucket={}, objectName={}, size={}, contentType={}, reason={}",
                    bucket, objectName, file.getSize(), contentType, e.getMessage(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件上传失败");
        }
        FileUploadVO vo=new FileUploadVO();
        String url=minioProperties.getEndpoint()+"/"+bucket+"/"+objectName;
        vo.setUrl(url);
        vo.setObjectName(objectName);
        return vo;
    }
}
