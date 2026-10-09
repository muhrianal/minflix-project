# Configuration

Exactly two config files, no `application.yml`:
- `src/main/resources/application-dev.yml` (profile `dev`): hardcoded local values.
- `src/main/resources/application-prod.yml` (profile `prod`): every value from an env var, **no defaults** — a missing env var stops startup with `Could not resolve placeholder 'X'`.

Mode profile `worker` (`dev,worker` / `prod,worker`) enables `TranscodeListener` and `Transcoder`. A worker also needs `SPRING_MAIN_WEB_APPLICATION_TYPE=none` (no web server).

## Properties side by side
| Property | dev | prod |
|---|---|---|
| `spring.data.mongodb.uri` | `mongodb://localhost:27017/minflix` | `${MONGODB_URI}` |
| `spring.data.mongodb.auto-index-creation` | `true` (unique email index) | `true` |
| `spring.data.mongodb.ssl.bundle` | — | `docdb` |
| `spring.ssl.bundle.pem.docdb.truststore.certificate` | — | `classpath:certs/global-bundle.pem` (Amazon RDS/DocumentDB CA bundle, committed) |
| `spring.rabbitmq.host` | `localhost` | `${RABBITMQ_HOST}` |
| `spring.rabbitmq.port` | `5672` | `5671` |
| `spring.rabbitmq.ssl.enabled` | — | `true` |
| `spring.rabbitmq.username` / `password` | `guest` / `guest` | `${RABBITMQ_USERNAME}` / `${RABBITMQ_PASSWORD}` |
| `spring.rabbitmq.listener.simple.concurrency` / `max-concurrency` / `prefetch` | `1` / `1` / `1` | same |
| `spring.rabbitmq.listener.simple.default-requeue-rejected` | `false` | `false` |
| `storage.bucket` | `minflix` | `${STORAGE_BUCKET}` |
| `storage.region` | `us-east-1` | `${AWS_REGION}` |
| `storage.endpoint` | `http://localhost:9000` (compose: `http://minio:9000`) | — (real S3) |
| `storage.public-endpoint` | `http://localhost:9000` (used only by the presigner; must be browser-reachable) | — |
| `storage.access-key` / `secret-key` | `minioadmin` / `minioadmin` | — (ECS task role, default credential chain) |
| `storage.playlist-base-url` | `http://localhost:9000/minflix` (includes bucket: path-style MinIO) | `${PUBLIC_BASE_URL}` (CloudFront, no bucket) |
| `jwt.secret` | dev-only string (32+ bytes) | `${JWT_SECRET}` |
| `app.cors-allowed-origin` | `http://localhost:5173` | `${PUBLIC_BASE_URL}` |
| `management.endpoints.web.exposure.include` | `health` | `health` |

Storage beans: `DevStorageConfig` (`@Profile("dev")`: endpoint override, path-style, static creds) and `ProdStorageConfig` (`@Profile("prod")`: region only, default credential chain).

## dev overrides from docker compose
Both `api` and `worker` containers set:
```
SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/minflix
SPRING_RABBITMQ_HOST=rabbitmq
STORAGE_ENDPOINT=http://minio:9000
STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY = MINIO_ROOT_USER / MINIO_ROOT_PASSWORD
```
`storage.public-endpoint` stays `http://localhost:9000` so presigned URLs work from the browser. Compose ports/credentials can be changed via `.env` (copy `.env.example`).

## prod env vars (injected by minflix-infra into the ECS task definitions)
| Env var | Source | Feeds |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | task definition | `prod` (api) / `prod,worker` (worker) |
| `SPRING_MAIN_WEB_APPLICATION_TYPE` | task definition, worker only | `none` |
| `MONGODB_URI` | Secrets Manager | `spring.data.mongodb.uri` (full DocumentDB URI incl. credentials) |
| `RABBITMQ_HOST` | task definition | `spring.rabbitmq.host` (Amazon MQ hostname) |
| `RABBITMQ_USERNAME` | task definition | `spring.rabbitmq.username` |
| `RABBITMQ_PASSWORD` | Secrets Manager | `spring.rabbitmq.password` |
| `JWT_SECRET` | Secrets Manager | `jwt.secret` (HS256 key, 32+ bytes) |
| `STORAGE_BUCKET` | task definition | `storage.bucket` |
| `AWS_REGION` | task definition | `storage.region` |
| `PUBLIC_BASE_URL` | task definition | `storage.playlist-base-url` and `app.cors-allowed-origin` |

No AWS access keys anywhere: S3 access comes from the ECS task role.

The API and the worker both need every variable above (the worker never uses `JWT_SECRET`/`PUBLIC_BASE_URL` for requests, but the same yml is loaded and it has no defaults).
