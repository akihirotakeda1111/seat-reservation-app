resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name_prefix}"
  retention_in_days = 14
}

resource "aws_iam_role" "execution" {
  name = "${local.name_prefix}-exec"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "execution" {
  name = "execution"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "WriteTaskLogStreams"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "${aws_cloudwatch_log_group.app.arn}:*"
      },
      {
        Sid      = "GetEcrAuthorizationToken"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "PullApplicationEcrImages"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
        ]
        Resource = aws_ecr_repository.app.arn
      },
      {
        Sid      = "ReadDatabaseCredentials"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = aws_db_instance.app.master_user_secret[0].secret_arn
      },
    ]
  })
}

resource "aws_ecs_cluster" "app" {
  name = local.name_prefix

  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_task_definition" "app" {
  # Preserve revisions used by running services and rollback after baseline updates.
  skip_destroy             = true
  family                   = "${local.name_prefix}-app"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = tostring(var.deployment.cpu)
  memory                   = tostring(var.deployment.memory)
  execution_role_arn       = aws_iam_role.execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "app"
      image     = var.application.image_digest == null ? "${aws_ecr_repository.app.repository_url}:bootstrap" : "${aws_ecr_repository.app.repository_url}@${var.application.image_digest}"
      essential = true
      environment = [
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.app.address}:5432/${aws_db_instance.app.db_name}?sslmode=require" },
        { name = "COGNITO_ISSUER_URI", value = var.application.cognito_issuer_uri },
        { name = "COGNITO_CLIENT_ID", value = var.application.cognito_client_id }
      ]
      secrets = [
        { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_db_instance.app.master_user_secret[0].secret_arn}:username::" },
        { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_db_instance.app.master_user_secret[0].secret_arn}:password::" }
      ]
      portMappings = [
        {
          containerPort = var.application.container_port
          protocol      = "tcp"
        }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "app"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "app" {
  lifecycle {
    # CD registers a release revision and starts one task, including the first release.
    ignore_changes = [task_definition, desired_count]
  }

  name                               = "${local.name_prefix}-app"
  cluster                            = aws_ecs_cluster.app.id
  task_definition                    = aws_ecs_task_definition.app.arn
  desired_count                      = var.deployment.desired_count
  wait_for_steady_state              = true
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  scheduling_strategy                = "REPLICA"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  health_check_grace_period_seconds  = var.deployment.health_check_grace_period_seconds
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"

  deployment_circuit_breaker {
    enable   = true
    rollback = var.deployment.rollback_enabled
  }

  network_configuration {
    subnets          = [for az in local.azs : aws_subnet.private[az].id]
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = var.application.container_port
  }

  depends_on = [
    aws_lb_listener.https,
    aws_iam_role_policy.execution,
    aws_route.private_default,
    aws_route_table_association.private,
    aws_vpc_security_group_egress_rule.ecs_https,
    aws_vpc_security_group_egress_rule.ecs_to_rds,
    aws_vpc_security_group_ingress_rule.rds_from_ecs,
  ]
}
