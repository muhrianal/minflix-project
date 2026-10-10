package com.minflix.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscoderTest {

    @Test
    void withAudioMapsFirstAudioTrackIntoEveryRendition() {
        List<String> command = Transcoder.ffmpegCommand("in.mp4", true);

        assertThat(String.join(" ", command)).contains(
                "-map [v720] -map 0:a:0 -map [v480] -map 0:a:0 -map [v360] -map 0:a:0",
                "-c:a aac -b:a 128k",
                "-var_stream_map v:0,a:0,name:720p v:1,a:1,name:480p v:2,a:2,name:360p");
    }

    @Test
    void withoutAudioProducesVideoOnlyRenditions() {
        List<String> command = Transcoder.ffmpegCommand("in.mp4", false);

        assertThat(command).doesNotContain("0:a:0", "-c:a", "-b:a");
        assertThat(String.join(" ", command)).contains(
                "-map [v720] -map [v480] -map [v360]",
                "-var_stream_map v:0,name:720p v:1,name:480p v:2,name:360p");
    }

    @Test
    void bothVariantsShareLadderSegmentingAndLayout() {
        for (boolean hasAudio : new boolean[] {true, false}) {
            List<String> command = Transcoder.ffmpegCommand("in.mp4", hasAudio);

            assertThat(command).startsWith("ffmpeg", "-y", "-i", "in.mp4").endsWith("%v/index.m3u8");
            assertThat(String.join(" ", command)).contains(
                    "scale=-2:720[v720]", "scale=-2:480[v480]", "scale=-2:360[v360]",
                    "-c:v libx264 -preset veryfast",
                    "-b:v:0 2800k -b:v:1 1400k -b:v:2 800k",
                    "-force_key_frames expr:gte(t,n_forced*6)",
                    "-f hls -hls_time 6 -hls_playlist_type vod",
                    "-master_pl_name master.m3u8",
                    "-hls_segment_filename %v/seg%d.ts");
        }
    }
}
