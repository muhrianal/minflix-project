package com.minflix.worker;

import com.minflix.video.Video;
import com.minflix.video.VideoRepository;
import com.minflix.video.VideoStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeListenerTest {

    @Mock
    private Transcoder transcoder;
    @Mock
    private VideoRepository videos;
    @InjectMocks
    private TranscodeListener listener;

    @Test
    void successfulJobLeavesStatusToTranscoder() throws Exception {
        listener.onJob(new TranscodeJob("v1"));

        verify(transcoder).transcode("v1");
        verifyNoInteractions(videos);
    }

    @Test
    void failedJobMarksVideoFailedWithoutRethrowing() throws Exception {
        Video v = new Video();
        v.setId("v1");
        v.setStatus(VideoStatus.PROCESSING);
        doThrow(new IllegalStateException("ffmpeg exited with code 234")).when(transcoder).transcode("v1");
        when(videos.findById("v1")).thenReturn(Optional.of(v));

        assertThatNoException().isThrownBy(() -> listener.onJob(new TranscodeJob("v1")));

        assertThat(v.getStatus()).isEqualTo(VideoStatus.FAILED);
        verify(videos).save(v);
    }

    @Test
    void failedJobForDeletedVideoIsSwallowed() throws Exception {
        doThrow(new IllegalStateException("gone")).when(transcoder).transcode("v1");
        when(videos.findById("v1")).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> listener.onJob(new TranscodeJob("v1")));
    }
}
