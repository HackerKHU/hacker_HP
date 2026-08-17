resource "aws_lb" "main" {
  name               = "${local.name}-alb"
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  idle_timeout               = 60
  enable_deletion_protection = false
}

resource "aws_lb_target_group" "api" {
  name        = "${local.name}-api-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # ★ Fargate awsvpc는 ip 타겟

  deregistration_delay = 30 # 기본 300초는 배포가 너무 느림

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# #156 — 443으로 리다이렉트한다. 평문 HTTP로 API를 직접 받지 않는다.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# #156 — api.khuhacker.com 인증서. DNS 검증이라 발급 자체는 이 리소스만으로 끝나지 않는다.
# AWS가 domain_validation_options로 내려주는 CNAME을 실제 DNS(khuhacker.com을 관리하는
# 곳 — 이 계정 Route53이 아니다)에 넣어야 상태가 PENDING_VALIDATION에서 ISSUED로 바뀐다.
# 그 CNAME은 outputs.tf의 acm_validation_record로 확인한다.
resource "aws_acm_certificate" "api" {
  domain_name       = "api.khuhacker.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  # TLS 1.2 이상만 받는다. ALB 기본값(2016-08)은 더 오래된 프로토콜까지 허용한다.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = aws_acm_certificate.api.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
