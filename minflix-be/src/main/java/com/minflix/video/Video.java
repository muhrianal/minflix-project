package com.minflix.video;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document("videos")
public class Video {
    @Id
    private String id;
    private String ownerId;
    private String title;
    private VideoStatus status;
    private String rawKey;
    private String masterPlaylistUrl;
    private Double durationSec;
    private Instant createdAt;
}
