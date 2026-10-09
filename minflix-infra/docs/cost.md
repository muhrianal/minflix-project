# Cost

This stack runs 24/7 once applied and is **not** free-tier friendly. Prices vary by region and change over time; check the AWS Pricing Calculator for `ap-southeast-1` (or your region) before applying. Order of magnitude if left running: **a few hundred USD per month**, dominated by the always-on managed services.

## Main drivers (roughly largest first)
| Item | Why it costs | Billed by |
|---|---|---|
| DocumentDB `db.t3.medium` instance | always-on instance + storage + I/O | instance-hours, GB-month, I/O |
| Fargate worker (2 vCPU / 4 GB / 50 GiB) | runs even when the queue is empty | vCPU-hours, GB-hours, extra ephemeral storage |
| Amazon MQ `mq.t3.micro` broker | always-on broker | broker-hours, storage |
| Application Load Balancer | fixed hourly charge + LCUs | hours, LCU-hours |
| Fargate api (0.5 vCPU / 1 GB) | always-on | vCPU-hours, GB-hours |
| Public IPv4 addresses | each task and the ALB use public IPv4 | per IP-hour |
| CloudFront + S3 | data transfer out, requests, storage of raw + HLS files | GB, requests |
| Secrets Manager, CloudWatch Logs, ECR | small | per secret, GB ingested/stored |

## How to cut cost
- Stop the worker when idle: `worker_desired_count = 0` in `terraform.tfvars`, then apply (or `aws ecs update-service --cluster minflix-prod --service worker --desired-count 0`). Jobs wait in the queue until it is back.
- `api_desired_count = 0` when nobody is using the site (the FE will show API errors).
- **Destroy when the environment is not in use:** `terraform destroy`. Buckets, ECR and secrets are set up to delete cleanly (`force_destroy`, `force_delete`, `recovery_window_in_days = 0`, no final snapshot). All videos and users are lost.
- Keep test videos short: worker time, storage and transfer scale with video length.
- `cloudfront_price_class = "PriceClass_100"` limits edge locations (cheaper, slower outside NA/EU).
