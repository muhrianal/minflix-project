package com.minflix.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** MinIO: the API/worker talk to storage.endpoint, the browser-facing presigned URLs use storage.public-endpoint. */
@Configuration
@Profile("dev")
public class DevStorageConfig {

    @Value("${storage.region}")
    private String region;
    @Value("${storage.access-key}")
    private String accessKey;
    @Value("${storage.secret-key}")
    private String secretKey;

    @Bean
    S3Client s3Client(@Value("${storage.endpoint}") String endpoint) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .region(Region.of(region))
                .credentialsProvider(credentials())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${storage.public-endpoint}") String publicEndpoint) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .region(Region.of(region))
                .credentialsProvider(credentials())
                .build();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
