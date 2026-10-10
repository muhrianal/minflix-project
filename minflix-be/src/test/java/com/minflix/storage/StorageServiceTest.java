package com.minflix.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StorageServiceTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner presigner = S3Presigner.builder()
            .endpointOverride(URI.create("http://localhost:9000"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
            .build();
    private final StorageService storage = new StorageService(s3, presigner, "minflix");

    @Test
    void presignedPutTargetsBucketKeyAndExpiresIn15Minutes() {
        URI url = URI.create(storage.presignPut("raw/v1/original.mp4", "video/mp4"));

        assertThat(url.getScheme() + "://" + url.getAuthority()).isEqualTo("http://localhost:9000");
        assertThat(url.getPath()).isEqualTo("/minflix/raw/v1/original.mp4");
        assertThat(url.getQuery())
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-Signature=")
                .contains("X-Amz-SignedHeaders=content-type;host");
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadReadsKeyFromBucketIntoTarget() {
        Path target = Path.of("target-file");

        storage.download("raw/v1/original.mp4", target);

        ArgumentCaptor<Consumer<GetObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).getObject(captor.capture(), eq(target));
        GetObjectRequest.Builder builder = GetObjectRequest.builder();
        captor.getValue().accept(builder);
        assertThat(builder.build().bucket()).isEqualTo("minflix");
        assertThat(builder.build().key()).isEqualTo("raw/v1/original.mp4");
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadWritesKeyWithContentType() throws Exception {
        Path source = Files.createTempFile("seg", ".ts");
        try {
            storage.upload(source, "hls/v1/720p/seg0.ts", "video/mp2t");

            ArgumentCaptor<Consumer<PutObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
            verify(s3).putObject(captor.capture(), any(RequestBody.class));
            PutObjectRequest.Builder builder = PutObjectRequest.builder();
            captor.getValue().accept(builder);
            PutObjectRequest request = builder.build();
            assertThat(request.bucket()).isEqualTo("minflix");
            assertThat(request.key()).isEqualTo("hls/v1/720p/seg0.ts");
            assertThat(request.contentType()).isEqualTo("video/mp2t");
        } finally {
            Files.delete(source);
        }
    }
}
