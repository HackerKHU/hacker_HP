# IAM 역할 2개 — Execution Role(ECS 서비스: ECR pull/로그/SSM 조회) vs
# Task Role(컨테이너 안 앱 코드: S3 read/write). S3 업로드 권한은 Task Role에 둔다 —
# Execution Role에 넣으면 동작하지 않는다 (docs/ops/infra.md).

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- Execution Role ---
resource "aws_iam_role" "ecs_execution" {
  name               = "${local.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# 시크릿 주입에 필요 — 이거 빼먹으면 태스크가 ResourceInitializationError로 죽음
resource "aws_iam_role_policy" "ecs_execution_ssm" {
  role = aws_iam_role.ecs_execution.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = "arn:aws:ssm:${local.region}:${data.aws_caller_identity.current.account_id}:parameter/hacker/dev/*"
    }]
  })
}

# --- Task Role ---
resource "aws_iam_role" "ecs_task" {
  name               = "${local.name}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "ecs_task" {
  role = aws_iam_role.ecs_task.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.uploads.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.uploads.arn
      },
      {
        # ECS Exec 용 (컨테이너 접속 디버깅)
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_ecs_cluster" "main" {
  name = "${local.name}-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled" # 켜면 CloudWatch 요금 발생
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT" # 온디맨드 대비 약 1/3
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name}-api"
  retention_in_days = 14 # 무제한으로 두면 요금이 쌓임
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"  # 0.5 vCPU
  memory                   = "1024" # JVM이라 512는 빠듯함
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64" # docs/ops/runbook.md의 빌드 플랫폼 주의 참고
  }

  container_definitions = jsonencode([{
    name      = "api"
    image     = "${aws_ecr_repository.api.repository_url}:latest"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "AWS_REGION", value = local.region },
      { name = "S3_BUCKET", value = aws_s3_bucket.uploads.id },
      # 프록시가 두 겹이라 Spring이 조립한 redirect_uri는 구글 등록값과 어긋난다. 절대 URI로 고정한다.
      { name = "OAUTH_REDIRECT_URI", value = var.oauth_redirect_uri },
      # 가입을 허용할 학교 이메일 도메인. 시크릿이 아니므로 SSM이 아니라 여기에 둔다.
      { name = "ALLOWED_EMAIL_DOMAIN", value = var.allowed_email_domain },
      { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=70 -XX:+UseSerialGC" }
    ]

    secrets = [
      { name = "DB_URL", valueFrom = aws_ssm_parameter.db_url.arn },
      { name = "DB_USERNAME", valueFrom = aws_ssm_parameter.db_username.arn },
      { name = "DB_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
      { name = "JWT_SECRET", valueFrom = aws_ssm_parameter.jwt_secret.arn },
      { name = "ADMIN_BOOTSTRAP_EMAIL", valueFrom = aws_ssm_parameter.admin_bootstrap_email.arn },
      { name = "ADMIN_BOOTSTRAP_TOKEN", valueFrom = aws_ssm_parameter.admin_bootstrap_token.arn },
      { name = "GOOGLE_CLIENT_ID", valueFrom = aws_ssm_parameter.google_client_id.arn },
      { name = "GOOGLE_CLIENT_SECRET", valueFrom = aws_ssm_parameter.google_client_secret.arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = local.region
        "awslogs-stream-prefix" = "api"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 90 # JVM 부팅 시간. 짧으면 계속 죽음
    }
  }])
}

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = 1

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 1
  }

  enable_execute_command = true # 디버깅용 컨테이너 접속

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = true # ★ NAT 없이 ECR pull 하려면 필수
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true # 배포 실패 시 자동 롤백
  }

  health_check_grace_period_seconds = 120

  # CI가 태스크 정의를 갱신하므로 terraform이 되돌리지 않게
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener.http]
}
