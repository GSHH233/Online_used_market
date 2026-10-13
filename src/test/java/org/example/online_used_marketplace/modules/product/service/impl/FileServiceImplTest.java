package org.example.online_used_marketplace.modules.product.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.config.MinioProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileServiceImplTest {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aKxkAAAAASUVORK5CYII=");
    private final MinioClient minio = mock(MinioClient.class);
    private final MinioProperties properties = properties();
    private final FileServiceImpl service = new FileServiceImpl(minio, properties);

    @Test
    void constructionDoesNotContactMinio() {
        verifyNoInteractions(minio);
    }

    @Test
    void validImageUsesDetectedMimeAndPreservesBucketAndUrl() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        var result = service.upload(file("photo.PNG", "text/html", PNG));

        ArgumentCaptor<PutObjectArgs> uploaded = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(uploaded.capture());
        assertThat(uploaded.getValue().contentType()).isEqualTo("image/png");
        assertThat(uploaded.getValue().objectSize()).isEqualTo(PNG.length);
        assertThat(uploaded.getValue().bucket()).isEqualTo("products");
        assertThat(uploaded.getValue().stream().readAllBytes()).isEqualTo(PNG);
        assertThat(result.getObjectName()).matches("[0-9a-f-]{36}\\.png");
        assertThat(result.getUrl()).isEqualTo(properties.getEndpoint() + "/products/" + result.getObjectName());
        verify(minio, never()).makeBucket(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "jpeg", "gif", "bmp"})
    void supportedRasterExtensionsMatchActualImageBytes(String extension) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String format = extension.equals("jpeg") ? "jpg" : extension;
        assertThat(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), format, bytes)).isTrue();
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        assertThat(service.upload(file("photo." + extension, null, bytes.toByteArray())).getObjectName())
                .endsWith("." + extension);
        verify(minio).putObject(any());
    }

    @Test
    void exactlyFiveMiBOfActualBytesIsAccepted() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        service.upload(file("photo.png", "image/png", Arrays.copyOf(PNG, MAX_BYTES)));
        ArgumentCaptor<PutObjectArgs> uploaded = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(uploaded.capture());
        assertThat(uploaded.getValue().objectSize()).isEqualTo(MAX_BYTES);
    }

    @Test
    void declaredSizeAboveFiveMiBIsRejectedBeforeMinio() {
        assertBadRequest(file("photo.png", "image/png", Arrays.copyOf(PNG, MAX_BYTES + 1)));
    }

    @Test
    void underReportedMultipartSizeCannotBypassActualByteLimit() {
        MultipartFile upload = new MockMultipartFile("file", "photo.png", "image/png",
                Arrays.copyOf(PNG, MAX_BYTES + 1)) {
            @Override
            public long getSize() {
                return 1;
            }
        };
        assertBadRequest(upload);
    }

    @Test
    void uploadedLengthComesFromValidatedBytesNotReportedSize() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        MultipartFile upload = new MockMultipartFile("file", "photo.png", "image/png", PNG) {
            @Override
            public long getSize() {
                return 1;
            }
        };
        service.upload(upload);
        ArgumentCaptor<PutObjectArgs> uploaded = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(uploaded.capture());
        assertThat(uploaded.getValue().objectSize()).isEqualTo(PNG.length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo.svg", "photo.html", "photo.exe", "photo", ".png", "photo.png.exe", "photo.png."})
    void unsupportedExtensionsAreRejectedEvenWhenBytesArePng(String name) {
        assertBadRequest(file(name, "image/png", PNG));
    }

    @Test
    void pngRenamedAsJpegIsRejected() {
        assertBadRequest(file("photo.jpg", "image/jpeg", PNG));
    }

    @Test
    void htmlDisguisedAsPngIsRejected() {
        assertBadRequest(file("photo.png", "image/png", "<html><script>alert(1)</script></html>".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void nullEmptyAndMissingNameAreBadRequests() {
        assertBadRequest(null);
        assertBadRequest(file("photo.png", "image/png", new byte[0]));
        assertBadRequest(file(null, "image/png", PNG));
        assertBadRequest(file(" ", "image/png", PNG));
    }

    @Test
    void unreadableContentIsBadRequest() throws Exception {
        MultipartFile upload = mock(MultipartFile.class);
        when(upload.getOriginalFilename()).thenReturn("photo.png");
        when(upload.getSize()).thenReturn(1L);
        when(upload.getInputStream()).thenThrow(new IOException("unreadable"));
        assertBadRequest(upload);
    }

    @Test
    void missingBucketIsCreatedLazilyAndSuccessfulInitializationIsCached() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        service.upload(file("first.png", null, PNG));
        service.upload(file("second.png", null, PNG));
        verify(minio, times(1)).bucketExists(any());
        verify(minio, times(1)).makeBucket(any(MakeBucketArgs.class));
        verify(minio, times(2)).putObject(any());
    }

    @Test
    void failedBucketCheckIsNotCachedAndCanBeRetried() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IOException("offline")).thenReturn(true);
        assertThatThrownBy(() -> service.upload(file("photo.png", null, PNG)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(500));
        verify(minio, never()).putObject(any());

        service.upload(file("photo.png", null, PNG));
        verify(minio, times(2)).bucketExists(any());
        verify(minio).putObject(any());
    }

    @Test
    void failedBucketCreationIsNotCachedAndCanBeRetried() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        doThrow(new IOException("offline")).doNothing().when(minio).makeBucket(any());
        assertThatThrownBy(() -> service.upload(file("photo.png", null, PNG)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(500));

        service.upload(file("photo.png", null, PNG));
        verify(minio, times(2)).bucketExists(any());
        verify(minio, times(2)).makeBucket(any());
        verify(minio).putObject(any());
    }

    @Test
    void concurrentUploadsInitializeBucketOnlyOnce() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> uploads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                uploads.add(executor.submit(() -> service.upload(file("photo.png", null, PNG))));
            }
            for (Future<?> upload : uploads) {
                upload.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        verify(minio, times(1)).bucketExists(any());
        verify(minio, times(1)).makeBucket(any());
        verify(minio, times(8)).putObject(any());
    }

    private void assertBadRequest(MultipartFile upload) {
        assertThatThrownBy(() -> service.upload(upload)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(400));
        verifyNoInteractions(minio);
    }

    private MockMultipartFile file(String name, String mime, byte[] bytes) {
        return new MockMultipartFile("file", name, mime, bytes);
    }

    private MinioProperties properties() {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio.example:9000");
        properties.setBucketName("products");
        return properties;
    }
}
