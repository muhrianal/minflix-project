resource "aws_ecs_cluster" "main" {
  name = "minflix-prod"
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/minflix-prod-api"
  retention_in_days = 14
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/minflix-prod-worker"
  retention_in_days = 14
}

locals {
  image = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"

  # Prod runtime configuration from the contract, shared by api and worker.
  app_environment = [
    { name = "RABBITMQ_HOST", value = local.rabbitmq_host },
    { name = "RABBITMQ_USERNAME", value = local.rabbitmq_username },
    { name = "STORAGE_BUCKET", value = aws_s3_bucket.media.bucket },
    { name = "AWS_REGION", value = var.region },
    { name = "PUBLIC_BASE_URL", value = "https://${aws_cloudfront_distribution.main.domain_name}" },
  ]
  app_secrets = [
    { name = "MONGODB_URI", valueFrom = aws_secretsmanager_secret.mongodb_uri.arn },
    { name = "RABBITMQ_PASSWORD", valueFrom = aws_secretsmanager_secret.rabbitmq_password.arn },
    { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt_secret.arn },
  ]
}

# --- api ---

resource "aws_ecs_task_definition" "api" {
  family                   = "minflix-prod-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "api"
    image        = local.image
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment  = concat([{ name = "SPRING_PROFILES_ACTIVE", value = "prod" }], local.app_environment)
    secrets      = local.app_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.api.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "api"
      }
    }
  }])
}

resource "aws_ecs_service" "api" {
  name                              = "api"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.api.arn
  desired_count                     = var.api_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.http]
}

# --- worker ---

resource "aws_ecs_task_definition" "worker" {
  family                   = "minflix-prod-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.worker_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  ephemeral_storage {
    size_in_gib = var.worker_ephemeral_storage_gib
  }

  container_definitions = jsonencode([{
    name      = "worker"
    image     = local.image
    essential = true
    environment = concat([
      { name = "SPRING_PROFILES_ACTIVE", value = "prod,worker" },
      { name = "SPRING_MAIN_WEB_APPLICATION_TYPE", value = "none" },
    ], local.app_environment)
    secrets = local.app_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.worker.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "worker"
      }
    }
  }])
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = true
  }
}
