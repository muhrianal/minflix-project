package com.minflix.video;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface VideoRepository extends MongoRepository<Video, String> {
    List<Video> findByStatusOrOwnerIdOrderByCreatedAtDesc(VideoStatus status, String ownerId);
}
