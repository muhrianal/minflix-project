# Transcoding

The worker (`com.minflix.worker.Transcoder`, Spring profile `worker`) turns one uploaded file into HLS with three renditions, using one `ffprobe` and one `ffmpeg` call via `ProcessBuilder`. All ffmpeg/ffprobe output is streamed to the worker log, prefixed `[ffmpeg]` / `[ffprobe]`.

## Steps
1. Load the Video, download `raw/{id}/original.<ext>` (the stored `rawKey`) into a temp dir as `input`.
2. Duration: `ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 input`
3. Transcode (run with the temp `hls/` dir as working directory).
4. Upload every file under `hls/` to `hls/{id}/...` with Content-Type `application/vnd.apple.mpegurl` (`.m3u8`) or `video/mp2t` (`.ts`).
5. Set `READY`, `durationSec`, `masterPlaylistUrl = {storage.playlist-base-url}/hls/{id}/master.m3u8`. Delete the temp dir.

Any exception: the listener logs it and sets `FAILED`. The message is acked (`default-requeue-rejected=false`), so a bad job is never redelivered.

## The ffmpeg command
```
ffmpeg -y -i input \
  -filter_complex "[0:v]split=3[s1][s2][s3];[s1]scale=-2:720[v720];[s2]scale=-2:480[v480];[s3]scale=-2:360[v360]" \
  -map [v720] -map 0:a  -map [v480] -map 0:a  -map [v360] -map 0:a \
  -c:v libx264 -preset veryfast \
  -b:v:0 2800k -b:v:1 1400k -b:v:2 800k \
  -force_key_frames "expr:gte(t,n_forced*6)" \
  -c:a aac -b:a 128k \
  -f hls -hls_time 6 -hls_playlist_type vod \
  -master_pl_name master.m3u8 \
  -hls_segment_filename "%v/seg%d.ts" \
  -var_stream_map "v:0,a:0,name:720p v:1,a:1,name:480p v:2,a:2,name:360p" \
  "%v/index.m3u8"
```

| Flag | Meaning |
|---|---|
| `-y` | overwrite outputs without asking |
| `-filter_complex ... split=3 ...` | decode once, split the video into 3 branches, scale each |
| `scale=-2:<h>` | target height; width keeps aspect ratio and is rounded to an even number (x264 needs even dimensions) |
| `-map [v720] -map 0:a ...` | output streams in order: video 0 + audio 0, video 1 + audio 1, video 2 + audio 2 |
| `-c:v libx264 -preset veryfast` | H.264 (plays everywhere), fast encode for a CPU-bound worker |
| `-b:v:N` | target bitrate for video output stream N |
| `-force_key_frames expr:gte(t,n_forced*6)` | keyframe every 6 s, so segments are exactly 6 s and aligned across renditions |
| `-c:a aac -b:a 128k` | AAC audio at 128 kbit/s for every rendition |
| `-f hls` | HLS muxer |
| `-hls_time 6` | target segment length in seconds |
| `-hls_playlist_type vod` | finished playlist with `#EXT-X-ENDLIST`; players know the full length |
| `-master_pl_name master.m3u8` | also write the master playlist listing all renditions with bandwidth/resolution |
| `-hls_segment_filename %v/seg%d.ts` | segment path; `%v` is the rendition name, `%d` the segment index |
| `-var_stream_map "v:0,a:0,name:720p ..."` | groups streams into renditions; `name:` makes `%v` = `720p`/`480p`/`360p` |
| `%v/index.m3u8` | per-rendition media playlist |

## Bitrate ladder
| Rendition | Height | Video | Audio |
|---|---|---|---|
| 720p | 720 | 2800k | 128k AAC |
| 480p | 480 | 1400k | 128k AAC |
| 360p | 360 | 800k | 128k AAC |

## Resulting storage layout
```
hls/{videoId}/master.m3u8
hls/{videoId}/720p/index.m3u8, seg0.ts, seg1.ts, ...
hls/{videoId}/480p/index.m3u8, seg0.ts, ...
hls/{videoId}/360p/index.m3u8, seg0.ts, ...
```
The master playlist references renditions relatively (`720p/index.m3u8`), so the same files work behind MinIO (dev) and CloudFront (prod).

## Limits
- Input must have an audio track (see decisions.md).
- One job at a time per worker (listener concurrency 1, prefetch 1). Scale by running more workers.
