resource "aws_mq_broker" "main" {
  broker_name                = "minflix-prod-mq"
  engine_type                = "RabbitMQ"
  engine_version             = "3.13"
  auto_minor_version_upgrade = true # required by Amazon MQ for RabbitMQ 3.13
  host_instance_type         = var.mq_instance_type
  deployment_mode            = "SINGLE_INSTANCE"
  publicly_accessible        = false
  subnet_ids                 = [aws_subnet.private[0].id]
  security_groups            = [aws_security_group.mq.id]

  user {
    username = local.rabbitmq_username
    password = random_password.mq.result
  }
}

locals {
  rabbitmq_username = "minflix"

  # Endpoint looks like amqps://b-xxxx.mq.<region>.on.aws:5671; the backend wants the bare hostname
  # (port 5671 and TLS are set in application-prod.yml).
  rabbitmq_host = regex("^amqps://([^:/]+):5671$", aws_mq_broker.main.instances[0].endpoints[0])[0]
}
