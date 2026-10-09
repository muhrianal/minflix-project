package com.minflix.worker;

import com.minflix.video.VideoRepository;
import com.minflix.video.VideoStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class TranscodeListener {

    private final Transcoder transcoder;
    private final VideoRepository videos;

    @RabbitListener(queues = QueueConfig.TRANSCODE_QUEUE)
    public void onJob(TranscodeJob job) {
        log.info("Transcoding video {}", job.videoId());
        try {
            transcoder.transcode(job.videoId());
            log.info("Video {} READY", job.videoId());
        } catch (Exception e) {
            // Caught once here so the job is acked and never redelivered.
            log.error("Transcode failed for video {}", job.videoId(), e);
            videos.findById(job.videoId()).ifPresent(v -> {
                v.setStatus(VideoStatus.FAILED);
                videos.save(v);
            });
        }
    }
}
