resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

resource "aws_iam_role" "github_actions" {
  name = "${local.name}-github-actions"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike = {
          # ★ 특정 레포로 제한 — 다른 사람의 레포에서 이 역할을 가져다 쓰지 못하게 한다.
          "token.actions.githubusercontent.com:sub" = "repo:HackerKHU/hacker_HP:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_actions" {
  role = aws_iam_role.github_actions.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart",
          "ecr:BatchGetImage", "ecr:DescribeImages"
        ]
        Resource = aws_ecr_repository.api.arn
      },
      {
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition",
          "ecs:DescribeServices", "ecs:UpdateService"
        ]
        Resource = "*"
      },
      {
        # ★ 이거 빼먹으면 RegisterTaskDefinition이 AccessDenied로 실패함
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.ecs_task.arn]
      }
    ]
  })
}
