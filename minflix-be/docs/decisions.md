# Decisions and assumptions

One line each. Contract (`../../docs/CONTRACT.md`) wins over anything here.

- Spring Boot 3.5.16 (latest 3.x at build time); Boot 4 skipped to stay on the documented 3.x APIs. jjwt 0.12.7, AWS SDK v2 2.55.12.
- MinIO images: the official `minio/minio` and `minio/mc` images are no longer pullable (Docker Hub and quay.io deny access), so dev uses the pinned `bitnamilegacy/minio:2025.7.23` and `bitnamilegacy/minio-client:2025.7.21`.
- MinIO CORS: MinIO community rejects per-bucket CORS (`mc cors set` -> "not implemented"), so CORS is server-wide via `MINIO_API_CORS_ALLOW_ORIGIN=*` on the minio container (dev allows all origins, as the contract says).
- MongoDB image pinned to `mongo:7.0.14`: the `mongo:7` tag pulled on 2026-10-09 had a 0-byte `docker-entrypoint.sh` ("exec format error").
- `rawKey` is stored on the Video at creation; the worker downloads that exact key instead of listing `raw/{id}/original.*`.
- Extension in `rawKey` is taken from `filename` and lower-cased; a filename without an extension gives `raw/{id}/original`.
- `GET /api/videos/{id}` uses the same visibility as the list: someone else's non-READY video returns 404 `Not found`.
- `POST /api/videos/{id}/complete` does not check the current status; calling it twice enqueues the job twice.
- The API does not check that the raw object exists before enqueueing; a missing upload makes the worker mark the video FAILED.
- Validation: only non-blank `email`/`password` and `title`/`filename`/`contentType` (400). Malformed JSON gives 400 `Malformed request body`.
- Missing/invalid/expired JWT gives 401 `{"message":"Unauthorized"}`; not-owner gives 403 `{"message":"Forbidden"}`.
- `durationSec` is the ffprobe container duration as a decimal number (e.g. `10.0`), not rounded.
- Transcoder assumes the input has an audio track (`-map 0:a`); a video-only upload fails and is marked FAILED.
- ffmpeg uses `-force_key_frames expr:gte(t,n_forced*6)` so every rendition has a keyframe exactly every 6 s: segments are 6 s and aligned across renditions (needed for clean quality switching).
- Listener `prefetch: 1` in addition to concurrency 1, so one worker never holds jobs it is not processing.
- Video JSON is a separate `VideoResponse` record so `rawKey` never leaves the API.
- No smoke test: the end-to-end curl flow in the README is the verification; a Testcontainers test would need ffmpeg plus three containers and was not cheap.
- Compose services `api`/`worker` also set `image: minflix-be:dev` so a pre-built image can be reused (and both share one build).
