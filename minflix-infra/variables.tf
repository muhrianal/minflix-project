variable "region" {
  description = "AWS region for everything except CloudFront (global)."
  type        = string
  default     = "ap-southeast-1"
}

variable "image_tag" {
  description = "Tag of the minflix-be image in ECR, used by both api and worker."
  type        = string
  default     = "latest"
}

variable "api_cpu" {
  type    = number
  default = 512
}

variable "api_memory" {
  type    = number
  default = 1024
}

variable "worker_cpu" {
  type    = number
  default = 2048
}

variable "worker_memory" {
  type    = number
  default = 4096
}

variable "worker_ephemeral_storage_gib" {
  description = "Task disk for raw downloads and FFmpeg output."
  type        = number
  default     = 50
}

variable "docdb_instance_class" {
  type    = string
  default = "db.t3.medium"
}

variable "mq_instance_type" {
  type    = string
  default = "mq.t3.micro"
}

variable "cloudfront_price_class" {
  type    = string
  default = "PriceClass_200"
}

variable "api_desired_count" {
  type    = number
  default = 1
}

variable "worker_desired_count" {
  description = "Set to 0 to stop the worker (and its cost) when idle."
  type        = number
  default     = 1
}
