# Local setup (dev)

## Ports
| Service | Port | Notes |
|---|---|---|
| API | 8080 | `http://localhost:8080` |
| Frontend (minflix-fe) | 5173 | allowed CORS origin |
| MinIO S3 API | 9000 | presigned URLs and HLS files |
| MinIO console | 9001 | `http://localhost:9001`, login `minioadmin` / `minioadmin` |
| RabbitMQ AMQP | 5672 | |
| RabbitMQ UI | 15672 | `http://localhost:15672`, login `guest` / `guest` |
| MongoDB | 27017 | database `minflix`, collections `users`, `videos` |

Ports and MinIO credentials can be changed by copying `.env.example` to `.env`.

## What minio-init does
One-shot container: creates bucket `minflix`, sets anonymous download on `minflix/hls` only. `raw/` stays private (anonymous GET -> 403). CORS is configured server-wide on the minio container (`MINIO_API_CORS_ALLOW_ORIGIN=*`), because MinIO community does not support per-bucket CORS.

## Inspecting things
```
# bucket contents
docker compose run --rm --entrypoint bash minio-init -c \
  'mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null && mc ls -r local/minflix'
# anonymous policy
docker compose run --rm --entrypoint bash minio-init -c \
  'mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null && mc anonymous list local/minflix'
# queue
docker compose exec rabbitmq rabbitmqctl list_queues name durable messages
# database
docker compose exec mongo mongosh minflix --eval 'db.videos.find().sort({createdAt:-1}).limit(5)'
# worker / ffmpeg output
docker compose logs -f worker
```

## Troubleshooting
**Presigned PUT fails with 403 `SignatureDoesNotMatch`**
- The `Content-Type` header of the PUT must equal the `contentType` sent to `POST /api/videos`.
- The URL host must be the one it was signed for. The API signs with `storage.public-endpoint` (`http://localhost:9000`). Do not rewrite it to `minio:9000`.

**Presigned URL contains `minio:9000`**
The browser cannot resolve `minio`. `storage.public-endpoint` must stay `http://localhost:9000`; only `STORAGE_ENDPOINT` is overridden to `http://minio:9000` in compose.

**CORS error on upload from the browser**
Check `curl -i -X OPTIONS http://localhost:9000/minflix/raw/x -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: PUT'` returns `Access-Control-Allow-Origin`. If not, the minio container lost `MINIO_API_CORS_ALLOW_ORIGIN`; recreate it.

**CORS error on the API**
The API allows only `app.cors-allowed-origin` (`http://localhost:5173`). A FE on another port is rejected.

**Video stays FAILED / `Cannot run program "ffprobe"`**
The worker needs `ffmpeg` and `ffprobe` on PATH. The Docker image has them. To run the worker from the IDE, install them first (`brew install ffmpeg`). Calling `complete` before the upload finished ends in FAILED.

**`docker compose up --build` fails with `fork/exec .../docker-buildx: no such file or directory`**
Compose needs the buildx plugin. Install it (e.g. `brew install docker-buildx` and link it into `~/.docker/cli-plugins/`), or build with the legacy builder: `DOCKER_BUILDKIT=0 docker build -t minflix-be:dev .` then `docker compose up -d`.

**Worker exits with `required a bean of type HttpSecurity`**
Only if `SecurityConfig.filterChain` loses its `@ConditionalOnWebApplication`: the worker runs with `SPRING_MAIN_WEB_APPLICATION_TYPE=none`.

**Mongo exits with `exec format error`**
Bad upstream image; compose pins `mongo:7.0.14`.
