package com.minflix.video;

import java.time.Instant;

/** The contract's Video shape (rawKey stays internal). */
public record VideoResponse(String id, String title, VideoStatus status, String ownerId,
                            String masterPlaylistUrl, Double durationSec, Instant createdAt) {

    static VideoResponse of(Video v) {
        return new VideoResponse(v.getId(), v.getTitle(), v.getStatus(), v.getOwnerId(),
                v.getMasterPlaylistUrl(), v.getDurationSec(), v.getCreatedAt());
    }
}
