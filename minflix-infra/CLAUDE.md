# minflix-infra

Terraform for the **prod** environment of minflix on AWS. The dev environment does NOT use Terraform (it is docker compose, in `minflix-be`).

The shared contract (`../docs/CONTRACT.md`, imported by the root `CLAUDE.md`) is the source of truth for what prod must look like: URLs, storage layout, and the env-var table "Prod runtime configuration" (the interface with `minflix-be`). Never change it unilaterally. If something is ambiguous, pick the simplest option and add one line to `docs/decisions.md`.

## Rules (apply to every change)
- **Never run `terraform apply`, `terraform destroy`, `terraform import`, or any AWS CLI command that creates, changes or deletes resources, and never push images.** The user does that. Allowed: `terraform fmt`, `terraform init`, `terraform validate`. `terraform plan` only if the user confirms AWS credentials are configured.
- Minimal and straightforward: ONE root module, flat `.tf` files by concern, no child modules, no workspaces, no remote state yet (local state, gitignored), no autoscaling, no custom domain, no WAF, no CI.
- No defensive extras: no `lifecycle` blocks unless required, no count/for_each feature toggles, no unused variables or outputs.
- No secrets in committed files. Generated secrets use `random_password` and go to Secrets Manager. `terraform.tfvars` is gitignored; commit only `terraform.tfvars.example`.
- Pin versions: Terraform >= 1.6, `aws` provider `~> 5.0`, `random` provider. `default_tags` on the provider: `Project=minflix`, `Env=prod`. Name resources `minflix-prod-*`.
- Never claim something works without checking it. Anything that needs a real AWS account is "unverified" (only fmt/validate can be verified here).

## Variables (with defaults in variables.tf)
`region` (ap-southeast-1), `image_tag` (latest), `api_cpu`/`api_memory` (512/1024), `worker_cpu`/`worker_memory` (2048/4096), `worker_ephemeral_storage_gib` (50), `docdb_instance_class` (db.t3.medium), `mq_instance_type` (mq.t3.micro), `cloudfront_price_class` (PriceClass_200), `api_desired_count` (1), `worker_desired_count` (1).

## Files
```
versions.tf    terraform + provider versions, provider "aws" with default_tags
variables.tf
network.tf     VPC, 2 AZs, 2 public + 2 private subnets, IGW, route tables, security groups
storage.tf     media bucket, frontend bucket, bucket policies, media bucket CORS
ecr.tf         one repo `minflix-be`
secrets.tf     random passwords + Secrets Manager secrets
database.tf    DocumentDB
mq.tf          Amazon MQ for RabbitMQ
iam.tf         ECS execution role, api task role, worker task role
alb.tf         ALB, target group, listener
ecs.tf         cluster, log groups, task definitions, services (api, worker)
cdn.tf         CloudFront (3 origins, 3 behaviors, OAC)
outputs.tf
terraform.tfvars.example
.gitignore     .terraform/, *.tfstate*, terraform.tfvars
```

## Architecture (prod)
```
Browser ──https──► CloudFront (<cf-domain>.cloudfront.net)
                     ├─ /          ► S3 frontend bucket   (OAC, private)
                     ├─ /hls/*     ► S3 media bucket      (OAC, private; only hls/* readable)
                     └─ /api/*     ► ALB (http:80, CloudFront IPs only) ► ECS api (Fargate :8080)
Browser ──presigned PUT──► S3 media bucket  raw/...      (CORS: CloudFront origin only)

ECS api ──► DocumentDB (private subnets, TLS)
        ──► Amazon MQ RabbitMQ (private subnets, amqps 5671) ──► ECS worker (FFmpeg) ──► S3 hls/ + DocumentDB
```

## Resource details

### Network
- VPC, 2 AZs. Public subnets: ALB and Fargate tasks (tasks get public IPs so they can reach ECR, Secrets Manager, CloudWatch and S3 WITHOUT a NAT gateway). Private subnets (no route to the internet): DocumentDB and Amazon MQ.
- Security groups: ALB (inbound 80 only from the CloudFront managed prefix list `com.amazonaws.global.cloudfront.origin-facing`); ECS tasks (api: inbound 8080 only from the ALB SG; worker: no inbound; both: all outbound); DocumentDB (27017 from the ECS SG); MQ (5671 from the ECS SG).

### Storage
- Media bucket (name `minflix-prod-media-<random suffix>`): all public access blocked, `force_destroy = true`. Policy: allow `cloudfront.amazonaws.com` `s3:GetObject` on `hls/*` ONLY, with condition `AWS:SourceArn` = the distribution ARN. `raw/` is never readable through CloudFront. CORS: allowed origin `https://<cf-domain>`, methods GET/PUT/HEAD, headers `*`.
- Frontend bucket: same blocks, `force_destroy = true`, policy allows the distribution to `s3:GetObject` on `*`.

### Secrets (Secrets Manager, `recovery_window_in_days = 0` so destroy/recreate is clean)
- `random_password` with `special = false` for: DocumentDB master password, MQ password, JWT secret (length 64).
- Secrets: `minflix-prod/jwt-secret`, `minflix-prod/mongodb-uri`, `minflix-prod/rabbitmq-password`.
- MongoDB URI value: `mongodb://<user>:<password>@<docdb-cluster-endpoint>:27017/minflix?authSource=admin&tls=true&replicaSet=rs0&retryWrites=false`.

### DocumentDB
`aws_docdb_cluster` (engine docdb, storage encrypted, `skip_final_snapshot = true`, backup retention 1) + one `aws_docdb_cluster_instance` of `docdb_instance_class`, subnet group on the private subnets. TLS stays ON (the app trusts the AWS CA bundle through a Spring SSL bundle).

### Amazon MQ
RabbitMQ, a current 3.x engine version, `SINGLE_INSTANCE`, `mq_instance_type`, `publicly_accessible = false`, one private subnet, user `minflix`. Output the broker hostname (no scheme/port) for `RABBITMQ_HOST`; derive it from the broker endpoint attribute and check the format.

### ECR + image
One repo `minflix-be`, `force_delete = true`. Both ECS services use `<repo-url>:<image_tag>`. Tasks run `X86_64` (`runtime_platform`), so images MUST be built `--platform linux/amd64`.

### IAM
- Execution role (both tasks): AWS managed `AmazonECSTaskExecutionRolePolicy` + `secretsmanager:GetSecretValue` on the three secrets.
- api task role: `s3:PutObject` on `<media-bucket>/raw/*` (presigning).
- worker task role: `s3:GetObject` on `raw/*`, `s3:PutObject` on `hls/*`.

### ALB + ECS
- ALB in public subnets, HTTP:80 listener forwarding to a target group (`target_type = ip`, port 8080, health check `GET /actuator/health`, expect 200). Health check grace period 60s on the api service.
- Cluster `minflix-prod`. CloudWatch log groups per service, retention 14 days.
- `api` service: image from ECR, container port 8080, env + secrets from the contract table with `SPRING_PROFILES_ACTIVE=prod`, registered with the target group.
- `worker` service: same image, `SPRING_PROFILES_ACTIVE=prod,worker`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `worker_cpu`/`worker_memory`, `ephemeral_storage` of `worker_ephemeral_storage_gib`, no load balancer.
- Both services in public subnets with `assign_public_ip = true`.
- Env vars exactly as in the contract table. `PUBLIC_BASE_URL` = `https://` + the CloudFront domain name.

### CloudFront (one distribution, no custom domain, default `*.cloudfront.net` certificate)
- One Origin Access Control (S3) shared by both bucket origins.
- Origins: `frontend` (S3), `media` (S3), `api` (the ALB DNS name as a custom origin, `http-only`).
- Default root object `index.html`. Viewer protocol: redirect to HTTPS everywhere.
- Behaviors:
  - default -> `frontend`: GET/HEAD, managed `CachingOptimized`.
  - `/hls/*` -> `media`: GET/HEAD, managed `CachingOptimized`.
  - `/api/*` -> `api`: all HTTP methods, managed `CachingDisabled`, origin request policy managed `AllViewerExceptHostHeader` (so the Authorization header reaches the API).
- Custom error responses for 403 and 404 -> `/index.html` with 200 (SPA routing). Known side effect: a missing HLS file also returns index.html; record in decisions.
- `price_class = cloudfront_price_class`.

### Outputs
`cloudfront_domain`, `cloudfront_distribution_id`, `frontend_bucket`, `media_bucket`, `ecr_repository_url`, `ecs_cluster_name`, `api_service_name`, `worker_service_name`.

## Documentation (write as you go, Markdown, in the repo)
- `README.md`: what this is, prerequisites (Terraform, AWS CLI + credentials, Docker with buildx), repo layout, link to deploy.md.
- `docs/architecture.md`: the ASCII diagram, one paragraph per component, and a table mapping every dev component to its prod replacement.
- `docs/deploy.md`: exact ordered steps, written for the user to copy-paste:
  1. First deploy: `terraform init`, `terraform apply -target=aws_ecr_repository.app` (so the repo exists), build + push the BE image (`--platform linux/amd64`), then full `terraform apply`.
  2. Build + deploy the FE (build, `s3 sync`, CloudFront invalidation) with values from `terraform output`.
  3. Smoke test: open the CloudFront URL, register, upload a small video, watch it process, play it.
  4. Updating the backend: push a new image, then `aws ecs update-service --cluster ... --service ... --force-new-deployment` for both `api` and `worker`.
  5. Logs: `aws logs tail ... --follow`.
  6. Teardown: `terraform destroy`.
  7. Troubleshooting: task cannot pull image (not pushed yet / wrong platform), health check failing, DocumentDB TLS errors, S3 CORS error on upload, FE route 404 after refresh.
- `docs/cost.md`: the main cost drivers (DocumentDB instance, Amazon MQ broker, ALB, always-on Fargate tasks, CloudFront/S3 transfer), rough monthly order of magnitude with the caveat that prices vary, and how to cut cost (`worker_desired_count = 0` when idle, destroy when not demoing). No invented exact prices.
- `docs/decisions.md`: every assumption or deviation, one line each (e.g. Amazon MQ chosen over SQS to keep one code path; no NAT gateway; local state; DocumentDB over Atlas; no origin-secret header between CloudFront and ALB).

## Working method
1. Output a short plan with the file list first.
2. Implement in order: (a) versions/variables/network, (b) storage + ecr + secrets, (c) database + mq, (d) iam + alb + ecs, (e) cdn + outputs, (f) docs.
3. After each step run `terraform fmt -recursive` and `terraform validate`.
4. At the end cross-check every env var in the contract table against the ECS task definitions, and every port/path against the diagram.
5. Report what you verified (fmt/validate) and what you did not (everything that needs a real AWS account).
