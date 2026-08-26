package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.config.MinioProperties;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.document.KnowledgeDocumentParser;
import org.example.online_used_marketplace.modules.ai.document.ParseContext;
import org.example.online_used_marketplace.modules.ai.document.ParsedDocument;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocumentUploadService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocumentUploadVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeReindexTriggerVO;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentUploadServiceImpl implements KnowledgeDocumentUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "markdown", "pdf", "doc", "docx");

    private final List<KnowledgeDocumentParser> parsers;
    private final KnowledgeIngestionProperties properties;
    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final ObjectMapper objectMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final MeterRegistry meterRegistry;
    private final ExecutorService parserExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "knowledge-parser");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public KnowledgeDocumentUploadVO upload(MultipartFile file,
                                            String title,
                                            KnowledgeSourceType sourceType,
                                            String tags,
                                            String sourceTrustLevel,
                                            String docVersion,
                                            String logicalKnowledgeId,
                                            boolean async) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文件不能为空");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String extension = extension(fileName);
        long started = System.currentTimeMillis();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 Markdown、PDF、DOC 和 DOCX");
        }
        validateDeclaredMime(extension, file.getContentType());
        long maxBytes = properties.getIngestion().getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文件超过大小限制");
        }
        try {
            byte[] bytes = file.getBytes();
            String fileHash = sha256(bytes);
            String detectedMime = new Tika().detect(bytes, fileName);
            validateMime(extension, detectedMime);

            KnowledgeDoc duplicate = knowledgeDocMapper.selectOne(new LambdaQueryWrapper<KnowledgeDoc>()
                    .eq(KnowledgeDoc::getFileHash, fileHash)
                    .eq(KnowledgeDoc::getParserVersion, properties.getIngestion().getParserVersion())
                    .eq(KnowledgeDoc::getChunkStrategyVersion, properties.getChunking().getStrategyVersion())
                    .eq(KnowledgeDoc::getEmbeddingModel, properties.getIngestion().getEmbeddingModel())
                    .orderByDesc(KnowledgeDoc::getId)
                    .last("LIMIT 1"));
            if (duplicate != null) {
                if (!StringUtils.hasText(duplicate.getActiveIndexVersion())) {
                    KnowledgeReindexTriggerVO retry = knowledgeIndexService.triggerIngest(duplicate.getId(), async);
                    KnowledgeDocumentUploadVO out = duplicateResponse(duplicate, fileName, detectedMime);
                    out.setDocument(knowledgeDocService.getVO(duplicate.getId()));
                    out.setTask(retry.getTask());
                    out.setWarnings(List.of("IDEMPOTENT_RETRY"));
                    recordParseMetric(extension, "idempotent_retry", started);
                    return out;
                }
                recordParseMetric(extension, "idempotent", started);
                return duplicateResponse(duplicate, fileName, detectedMime);
            }

            KnowledgeDocumentParser parser = parsers.stream()
                    .filter(candidate -> candidate.supports(detectedMime, fileName))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, "没有可用的文档解析器"));
            ParseContext context = ParseContext.builder()
                    .fileName(fileName)
                    .mimeType(detectedMime)
                    .fileHash(fileHash)
                    .parserVersion(properties.getIngestion().getParserVersion())
                    .maxExtractedChars(properties.getIngestion().getMaxExtractedChars())
                    .maxPdfPages(properties.getIngestion().getMaxPdfPages())
                    .build();
            ParsedDocument parsed = parseWithTimeout(parser, bytes, fileName, context);
            if (!StringUtils.hasText(parsed.getNormalizedText())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文档未提取到有效文本");
            }
            String sourceUri = storeRaw(bytes, fileName, detectedMime, fileHash);

            KnowledgeDocCreateRequestDTO dto = new KnowledgeDocCreateRequestDTO();
            dto.setTitle(StringUtils.hasText(title) ? title.trim() : parsed.getTitle());
            dto.setContent(parsed.getNormalizedText());
            dto.setSourceType(sourceType == null ? KnowledgeSourceType.GUIDE : sourceType);
            dto.setSourceUri(sourceUri);
            dto.setTags(tags);
            dto.setSourceTrustLevel(sourceTrustLevel);
            dto.setDocVersion(StringUtils.hasText(docVersion) ? docVersion : "v1");
            dto.setLogicalKnowledgeId(logicalKnowledgeId);
            dto.setFileName(fileName);
            dto.setMimeType(detectedMime);
            dto.setFileHash(fileHash);
            dto.setParserType(parsed.getParserType());
            dto.setParserVersion(parsed.getParserVersion());
            dto.setStructureJson(objectMapper.writeValueAsString(parsed));
            dto.setChunkStrategyVersion(properties.getChunking().getStrategyVersion());
            dto.setEmbeddingModel(properties.getIngestion().getEmbeddingModel());
            KnowledgeDocVO document = knowledgeDocService.createDoc(dto);
            KnowledgeReindexTriggerVO trigger = knowledgeIndexService.triggerIngest(document.getId(), async);

            KnowledgeDocumentUploadVO out = new KnowledgeDocumentUploadVO();
            out.setDocument(async ? document : knowledgeDocService.getVO(document.getId()));
            out.setTask(trigger.getTask());
            out.setFileName(fileName);
            out.setMimeType(detectedMime);
            out.setFileHash(fileHash);
            out.setParserType(parsed.getParserType());
            out.setSectionCount(parsed.getSections().size());
            out.setWarnings(parsed.getWarnings());
            recordParseMetric(extension, "success", started);
            return out;
        } catch (BusinessException ex) {
            recordParseMetric(extension, "rejected", started);
            throw ex;
        } catch (Exception ex) {
            recordParseMetric(extension, "failed", started);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识文档上传失败: " + ex.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        parserExecutor.shutdownNow();
    }

    private ParsedDocument parseWithTimeout(KnowledgeDocumentParser parser,
                                            byte[] bytes,
                                            String fileName,
                                            ParseContext context) throws Exception {
        Future<ParsedDocument> future = parserExecutor.submit(() ->
                parser.parse(new NamedByteArrayResource(bytes, fileName), context));
        try {
            return future.get(properties.getIngestion().getParserTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "文档解析超时(" + Duration.ofMillis(properties.getIngestion().getParserTimeoutMs()) + ")");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文档解析被中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            String message = StringUtils.hasText(cause.getMessage())
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档解析失败: " + message);
        }
    }

    private String storeRaw(byte[] bytes, String fileName, String mimeType, String fileHash) throws Exception {
        String bucket = properties.getIngestion().getRawBucket();
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        String objectName = fileHash.substring(0, 12) + "/" + fileName;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .contentType(mimeType)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .build());
        return minioProperties.getEndpoint() + "/" + bucket + "/" + objectName;
    }

    private KnowledgeDocumentUploadVO duplicateResponse(KnowledgeDoc duplicate, String fileName, String mimeType) {
        KnowledgeDocumentUploadVO out = new KnowledgeDocumentUploadVO();
        out.setDocument(knowledgeDocService.getVO(duplicate.getId()));
        out.setFileName(fileName);
        out.setMimeType(mimeType);
        out.setFileHash(duplicate.getFileHash());
        out.setParserType(duplicate.getParserType());
        out.setSectionCount(0);
        out.setWarnings(List.of("IDEMPOTENT_HIT"));
        return out;
    }

    private void validateMime(String extension, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        boolean valid = switch (extension) {
            case "md", "markdown" -> mime.startsWith("text/") || mime.contains("markdown");
            case "pdf" -> mime.equals("application/pdf");
            case "doc" -> mime.contains("msword") || mime.contains("msoffice");
            case "docx" -> mime.contains("wordprocessingml") || mime.contains("officedocument");
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "文件扩展名与真实MIME不一致: " + mimeType);
        }
    }

    private void validateDeclaredMime(String extension, String declaredMime) {
        if (!StringUtils.hasText(declaredMime)
                || "application/octet-stream".equalsIgnoreCase(declaredMime)) {
            return;
        }
        String mime = declaredMime.toLowerCase(Locale.ROOT);
        boolean compatible = switch (extension) {
            case "md", "markdown" -> mime.startsWith("text/") || mime.contains("markdown");
            case "pdf" -> mime.contains("pdf");
            case "doc" -> mime.contains("msword") || mime.contains("msoffice");
            case "docx" -> mime.contains("wordprocessingml") || mime.contains("officedocument");
            default -> false;
        };
        if (!compatible) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "Declared Content-Type does not match file extension: " + declaredMime);
        }
    }

    private String safeFileName(String original) {
        if (!StringUtils.hasText(original)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
        }
        String value = original.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(value) || value.length() > 255 || value.contains("..")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不合法");
        }
        return value;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void recordParseMetric(String format, String status, long started) {
        meterRegistry.counter("rag_ingest_parse_total", "format", format, "status", status)
                .increment();
        Timer.builder("rag_ingest_parse_duration_seconds")
                .tag("format", format)
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - started)));
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String fileName;

        private NamedByteArrayResource(byte[] byteArray, String fileName) {
            super(byteArray);
            this.fileName = fileName;
        }

        @Override
        public String getFilename() {
            return fileName;
        }
    }
}
