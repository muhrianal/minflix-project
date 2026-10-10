package com.minflix.worker;

import com.minflix.storage.StorageService;
import com.minflix.video.Video;
import com.minflix.video.VideoRepository;
import com.minflix.video.VideoStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
@Profile("worker")
public class Transcoder {

    private final StorageService storage;
    private final VideoRepository videos;
    private final String playlistBaseUrl;

    public Transcoder(StorageService storage, VideoRepository videos,
                      @Value("${storage.playlist-base-url}") String playlistBaseUrl) {
        this.storage = storage;
        this.videos = videos;
        this.playlistBaseUrl = playlistBaseUrl;
    }

    public void transcode(String videoId) throws IOException, InterruptedException {
        Video video = videos.findById(videoId).orElseThrow();
        Path workDir = Files.createTempDirectory("transcode-" + videoId);
        try {
            Path input = workDir.resolve("input");
            storage.download(video.getRawKey(), input);

            double duration = Double.parseDouble(run(workDir, List.of(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", input.toString())).trim());

            // Screen recordings and similar uploads often have no audio track; then the renditions are video-only.
            boolean hasAudio = !run(workDir, List.of(
                    "ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index",
                    "-of", "csv=p=0", input.toString())).isBlank();

            Path out = workDir.resolve("hls");
            Files.createDirectories(out);
            run(out, ffmpegCommand(input.toString(), hasAudio));

            try (Stream<Path> files = Files.walk(out)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String key = "hls/" + videoId + "/" + out.relativize(file).toString().replace('\\', '/');
                    String type = key.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t";
                    storage.upload(file, key, type);
                }
            }

            video.setStatus(VideoStatus.READY);
            video.setDurationSec(duration);
            video.setMasterPlaylistUrl(playlistBaseUrl + "/hls/" + videoId + "/master.m3u8");
            videos.save(video);
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    /** One ffmpeg pass: 720p/480p/360p HLS renditions plus master.m3u8; audio streams only when the input has audio. */
    static List<String> ffmpegCommand(String input, boolean hasAudio) {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-i", input,
                "-filter_complex",
                "[0:v]split=3[s1][s2][s3];[s1]scale=-2:720[v720];[s2]scale=-2:480[v480];[s3]scale=-2:360[v360]"));
        for (String rendition : List.of("[v720]", "[v480]", "[v360]")) {
            command.addAll(List.of("-map", rendition));
            if (hasAudio) {
                command.addAll(List.of("-map", "0:a:0"));
            }
        }
        command.addAll(List.of(
                "-c:v", "libx264", "-preset", "veryfast",
                "-b:v:0", "2800k", "-b:v:1", "1400k", "-b:v:2", "800k",
                "-force_key_frames", "expr:gte(t,n_forced*6)"));
        if (hasAudio) {
            command.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
        }
        command.addAll(List.of(
                "-f", "hls", "-hls_time", "6", "-hls_playlist_type", "vod",
                "-master_pl_name", "master.m3u8",
                "-hls_segment_filename", "%v/seg%d.ts",
                "-var_stream_map", hasAudio
                        ? "v:0,a:0,name:720p v:1,a:1,name:480p v:2,a:2,name:360p"
                        : "v:0,name:720p v:1,name:480p v:2,name:360p",
                "%v/index.m3u8"));
        return command;
    }

    /** Runs a command in dir, logs its combined output line by line, returns stdout+stderr; fails on non-zero exit. */
    private static String run(Path dir, List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}] {}", command.get(0), line);
                output.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(command.get(0) + " exited with code " + exit);
        }
        return output.toString();
    }
}
