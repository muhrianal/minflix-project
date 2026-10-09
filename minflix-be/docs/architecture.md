# Architecture

## Components (dev)
```
 Browser (FE :5173, hls.js)
   |  1. /api/* (JSON + JWT)            2. PUT raw file (presigned URL)      5. GET master.m3u8 + segments
   v                                       v                                     v
 +-------------+   publish job   +-----------+                     +------------------------------+
 | api :8080   | --------------> | RabbitMQ  |                     | MinIO :9000  bucket minflix  |
 | profile dev |                 | :5672     |                     |  raw/  (private)             |
 +-------------+                 +-----------+                     |  hls/  (anonymous download)  |
   |  ^                               | 3. consume                 +------------------------------+
   v  |                               v                                ^  4. download raw,
 +-------------+   update status +-----------------+  ffmpeg            |     upload hls/
 | MongoDB     | <-------------- | worker          | -------------------+
 | :27017      |                 | dev,worker      |
 +-------------+                 +-----------------+
```

## Components (prod, provisioned by minflix-infra)
```
 Browser
   |
   v
 CloudFront  https://<cf-domain>
   |-- /        -> FE static files
   |-- /api/*   -> ALB -> ECS Fargate service "api"   (profile prod)
   |-- /hls/*   -> S3 bucket (private, CloudFront-only access)
   |
 Browser --PUT presigned URL--> S3 (raw/, on the S3 domain)

 api ----publish----> Amazon MQ (RabbitMQ, amqps 5671) ----> ECS Fargate service "worker" (profile prod,worker)
 api, worker -------> Amazon DocumentDB (TLS, RDS CA bundle)
 worker ------------> S3 (download raw/, upload hls/)
 Credentials: ECS task role (S3), Secrets Manager (MONGODB_URI, RABBITMQ_PASSWORD, JWT_SECRET)
```

## Flows
**Upload**
1. `POST /api/videos {title, filename, contentType}` -> Video saved as `UPLOADING`, `rawKey = raw/{id}/original.<ext>`; API returns a 15-minute presigned PUT URL bound to `contentType`.
2. Browser `PUT`s the file to that URL with the same `Content-Type`. Bytes go straight to storage.
3. `POST /api/videos/{id}/complete` (owner only) -> `PROCESSING`, `TranscodeJob{videoId}` published to queue `transcode`.

**Processing**
4. Worker consumes the job (one at a time), downloads the raw file, runs ffprobe + ffmpeg, uploads `hls/{id}/...`, sets `READY` + `durationSec` + `masterPlaylistUrl`. Any failure -> `FAILED`, job acked (no redelivery loop).

**Watch**
5. FE polls `GET /api/videos/{id}` until `READY`, then gives `masterPlaylistUrl` to hls.js, which fetches the master playlist, picks a rendition and loads segments directly from storage/CDN.

## Why each piece exists, and what replaces it in prod
| Component | Why | dev | prod |
|---|---|---|---|
| Presigned PUT URL | Large uploads bypass the API: no memory/timeouts on the API, no storage credentials in the browser | MinIO presign (host `localhost:9000`) | S3 presign (task role credentials) |
| Queue | Decouples a seconds-long request from a minutes-long CPU job; survives worker restarts (durable queue) | RabbitMQ container | Amazon MQ for RabbitMQ |
| Worker | FFmpeg is CPU heavy; scale/isolate it separately from the API | `worker` container or IDE run | ECS service `worker` |
| HLS | Small segments over plain HTTP; ABR lets the player switch quality with bandwidth; cacheable | files in MinIO | files in S3 |
| CDN | Serves segments close to viewers; single origin for FE/API/HLS; keeps the bucket private | none (MinIO anonymous read on `hls/`) | CloudFront |
| Database | Users and video metadata/status | MongoDB container | DocumentDB |

One Docker image runs both `api` and `worker`; the mode is the Spring profile (`worker`) plus `SPRING_MAIN_WEB_APPLICATION_TYPE=none`.
