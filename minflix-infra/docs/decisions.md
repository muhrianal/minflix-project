# Decisions and assumptions

One line each. The contract (`../../docs/CONTRACT.md`) wins over anything here.

- **SPA routing via a CloudFront Function, not distribution-wide 403/404 custom error responses.** CLAUDE.md asked for custom error responses, but those apply to every behavior, so the API's own `403 {"message":"Forbidden"}` and `404 {"message":"Not found"}` would reach the browser as `200 index.html`, breaking the contract. The function (viewer-request, default behavior only) rewrites paths without a file extension to `/index.html`; `/api/*` and `/hls/*` errors pass through unchanged. Side effect: a missing FE asset or HLS file returns S3's 403 instead of index.html.
- Amazon MQ (RabbitMQ) instead of SQS: the backend keeps one code path (Spring AMQP) for dev and prod.
- No NAT gateway: Fargate tasks sit in public subnets with public IPs (inbound still blocked by security groups); DocumentDB and MQ sit in private subnets with no internet route.
- Local Terraform state (gitignored); no remote backend yet. `.terraform.lock.hcl` is committed.
- DocumentDB instead of MongoDB Atlas: everything stays in one AWS account and VPC; TLS on with the RDS CA bundle the backend already ships.
- No origin-secret header between CloudFront and the ALB: the ALB security group only allows the CloudFront origin-facing prefix list. Any CloudFront distribution (not only ours) could still reach the ALB.
- ALB listener is HTTP:80 and the CloudFront → ALB hop is `http-only` (no custom domain/certificate for the ALB).
- DocumentDB engine 5.0.0, one instance, backup retention 1 day, no final snapshot (demo environment).
- Amazon MQ RabbitMQ 3.13 (current 3.x), which requires `auto_minor_version_upgrade = true`. One broker in private subnet 0.
- `RABBITMQ_HOST` is parsed from the broker endpoint `amqps://<host>:5671` with a regex; the format is assumed from AWS docs and unverified until apply.
- DocumentDB master user `minflix`; MongoDB URI uses database `minflix`, `authSource=admin`, `replicaSet=rs0`, `retryWrites=false` (DocumentDB does not support retryable writes).
- Passwords are alphanumeric (`special = false`): no URI escaping and no characters Amazon MQ rejects.
- One security group per ECS service (api, worker) so only the api accepts traffic from the ALB; DocumentDB and MQ allow both.
- `/api/*` behavior uses `Managed-AllViewerExceptHostHeader` so `Authorization`, `Content-Type` and query strings reach the API; caching disabled.
- The media bucket policy only grants CloudFront `s3:GetObject` on `hls/*`; `raw/*` is reachable only through presigned URLs signed by the api task role.
- The api task role has only `s3:PutObject` on `raw/*`: that is what the presigned PUT is authorized with.
- Terraform runs fine from the official `hashicorp/terraform` Docker image if Terraform is not installed (that is how fmt/validate were checked here).
