package com.minflix.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.nio.file.Path;
import java.time.Duration;

@Service
public class StorageService {

    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public StorageService(S3Client s3, S3Presigner presigner, @Value("${storage.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public String presignPut(String key, String contentType) {
        return presigner.presignPutObject(p -> p
                        .signatureDuration(UPLOAD_URL_TTL)
                        .putObjectRequest(o -> o.bucket(bucket).key(key).contentType(contentType)))
                .url().toString();
    }

    public void download(String key, Path target) {
        s3.getObject(o -> o.bucket(bucket).key(key), target);
    }

    public void upload(Path source, String key, String contentType) {
        s3.putObject(o -> o.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(source));
    }
}
