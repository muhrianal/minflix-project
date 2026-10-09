package com.minflix.video;

import com.minflix.storage.StorageService;
import com.minflix.worker.QueueConfig;
import com.minflix.worker.TranscodeJob;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoRepository videos;
    private final StorageService storage;
    private final RabbitTemplate rabbit;

    public CreateVideoResponse create(String userId, CreateVideoRequest req) {
        if (!StringUtils.hasText(req.title()) || !StringUtils.hasText(req.filename())
                || !StringUtils.hasText(req.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title, filename and contentType are required");
        }
        Video video = new Video();
        video.setOwnerId(userId);
        video.setTitle(req.title());
        video.setStatus(VideoStatus.UPLOADING);
        video.setCreatedAt(Instant.now());
        video = videos.save(video);

        String ext = StringUtils.getFilenameExtension(req.filename());
        video.setRawKey("raw/" + video.getId() + "/original" + (ext == null ? "" : "." + ext.toLowerCase()));
        videos.save(video);

        return new CreateVideoResponse(video.getId(), storage.presignPut(video.getRawKey(), req.contentType()));
    }

    public Video complete(String userId, String id) {
        Video video = videos.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!video.getOwnerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        video.setStatus(VideoStatus.PROCESSING);
        videos.save(video);
        rabbit.convertAndSend(QueueConfig.TRANSCODE_QUEUE, new TranscodeJob(video.getId()));
        return video;
    }

    public List<Video> list(String userId) {
        return videos.findByStatusOrOwnerIdOrderByCreatedAtDesc(VideoStatus.READY, userId);
    }

    /** Same visibility as the list: READY videos, or the caller's own in any status. */
    public Video get(String userId, String id) {
        return videos.findById(id)
                .filter(v -> v.getStatus() == VideoStatus.READY || v.getOwnerId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }
}
