package com.minflix.video;

import com.minflix.storage.StorageService;
import com.minflix.worker.QueueConfig;
import com.minflix.worker.TranscodeJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoServiceTest {

    @Mock
    private VideoRepository videos;
    @Mock
    private StorageService storage;
    @Mock
    private RabbitTemplate rabbit;
    @InjectMocks
    private VideoService service;

    @Test
    void createStoresUploadingVideoAndPresignsRawKey() {
        when(videos.save(any())).thenAnswer(inv -> {
            Video v = inv.getArgument(0);
            v.setId("v1");
            return v;
        });
        when(storage.presignPut("raw/v1/original.mp4", "video/mp4")).thenReturn("http://upload");

        CreateVideoResponse response = service.create("user-1", new CreateVideoRequest("T", "Clip.MP4", "video/mp4"));

        assertThat(response).isEqualTo(new CreateVideoResponse("v1", "http://upload"));
        Video saved = savedVideo();
        assertThat(saved.getOwnerId()).isEqualTo("user-1");
        assertThat(saved.getTitle()).isEqualTo("T");
        assertThat(saved.getStatus()).isEqualTo(VideoStatus.UPLOADING);
        assertThat(saved.getRawKey()).isEqualTo("raw/v1/original.mp4");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getMasterPlaylistUrl()).isNull();
    }

    @Test
    void createWithoutExtensionUsesBareOriginalKey() {
        when(videos.save(any())).thenAnswer(inv -> {
            Video v = inv.getArgument(0);
            v.setId("v1");
            return v;
        });

        service.create("user-1", new CreateVideoRequest("T", "clip", "video/mp4"));

        verify(storage).presignPut("raw/v1/original", "video/mp4");
    }

    @Test
    void createWithMissingFieldIs400() {
        for (CreateVideoRequest req : List.of(
                new CreateVideoRequest(" ", "a.mp4", "video/mp4"),
                new CreateVideoRequest("T", null, "video/mp4"),
                new CreateVideoRequest("T", "a.mp4", ""))) {
            assertThatThrownBy(() -> service.create("user-1", req))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(videos, storage);
    }

    @Test
    void completeByOwnerSetsProcessingAndEnqueuesJob() {
        Video v = video("v1", "user-1", VideoStatus.UPLOADING);
        when(videos.findById("v1")).thenReturn(Optional.of(v));

        Video result = service.complete("user-1", "v1");

        assertThat(result.getStatus()).isEqualTo(VideoStatus.PROCESSING);
        verify(videos).save(v);
        verify(rabbit).convertAndSend(QueueConfig.TRANSCODE_QUEUE, new TranscodeJob("v1"));
    }

    @Test
    void completeByAnotherUserIs403AndEnqueuesNothing() {
        when(videos.findById("v1")).thenReturn(Optional.of(video("v1", "user-1", VideoStatus.UPLOADING)));

        assertStatus(() -> service.complete("user-2", "v1"), HttpStatus.FORBIDDEN);
        verify(videos, never()).save(any());
        verifyNoInteractions(rabbit);
    }

    @Test
    void completeUnknownVideoIs404() {
        when(videos.findById("missing")).thenReturn(Optional.empty());

        assertStatus(() -> service.complete("user-1", "missing"), HttpStatus.NOT_FOUND);
        verifyNoInteractions(rabbit);
    }

    @Test
    void listIsReadyOrOwn() {
        List<Video> expected = List.of(video("v1", "user-1", VideoStatus.PROCESSING));
        when(videos.findByStatusOrOwnerIdOrderByCreatedAtDesc(VideoStatus.READY, "user-1")).thenReturn(expected);

        assertThat(service.list("user-1")).isEqualTo(expected);
    }

    @Test
    void getReturnsAnyoneReadyVideo() {
        Video v = video("v1", "user-1", VideoStatus.READY);
        when(videos.findById("v1")).thenReturn(Optional.of(v));

        assertThat(service.get("user-2", "v1")).isSameAs(v);
    }

    @Test
    void getReturnsOwnVideoInAnyStatus() {
        for (VideoStatus status : VideoStatus.values()) {
            Video v = video("v1", "user-1", status);
            when(videos.findById("v1")).thenReturn(Optional.of(v));

            assertThat(service.get("user-1", "v1")).isSameAs(v);
        }
    }

    @Test
    void getHidesSomeoneElsesUnfinishedVideoAs404() {
        for (VideoStatus status : List.of(VideoStatus.UPLOADING, VideoStatus.PROCESSING, VideoStatus.FAILED)) {
            when(videos.findById("v1")).thenReturn(Optional.of(video("v1", "user-1", status)));

            assertStatus(() -> service.get("user-2", "v1"), HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void getUnknownVideoIs404() {
        when(videos.findById("missing")).thenReturn(Optional.empty());

        assertStatus(() -> service.get("user-1", "missing"), HttpStatus.NOT_FOUND);
    }

    private Video savedVideo() {
        ArgumentCaptor<Video> captor = ArgumentCaptor.forClass(Video.class);
        verify(videos, times(2)).save(captor.capture());
        return captor.getValue();
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(expected);
    }

    private static Video video(String id, String ownerId, VideoStatus status) {
        Video v = new Video();
        v.setId(id);
        v.setOwnerId(ownerId);
        v.setStatus(status);
        return v;
    }
}
