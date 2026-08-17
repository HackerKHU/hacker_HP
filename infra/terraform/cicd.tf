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
          #
          # 조직/레포 "이름"이 아니라 "이름@불변ID" 형식이다. 실제 배포에서
          # repo:HackerKHU/hacker_HP:*로 걸었다가 AssumeRoleWithWebIdentity가
          # 전부 AccessDenied로 거부됐다 — CloudTrail에 찍힌 실제 sub 값은
          # repo:HackerKHU@311740447/hacker_HP@1319201406:ref:refs/heads/develop였다.
          # GitHub이 레포 이름 변경/재사용으로 sub를 탈취하는 경로를 막으려고
          # 불변 ID를 포함시킨다. 이름만으로는 더 이상 매치되지 않는다.
          "token.actions.githubusercontent.com:sub" = "repo:HackerKHU@311740447/hacker_HP@1319201406:*"
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
