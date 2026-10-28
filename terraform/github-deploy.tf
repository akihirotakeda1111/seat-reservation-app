resource "aws_ssm_parameter" "deployment" {
  count = var.github_repository != "" ? 1 : 0
  name  = "/${var.project_name}/${var.environment}/deployment"
  type  = "String"
  value = jsonencode({
    baseline_task_definition = module.backend.resource_identifiers.baseline_task_definition_arn
    cluster                  = module.backend.resource_identifiers.ecs_cluster_arn
    service                  = module.backend.resource_identifiers.ecs_service_name
    target_group             = module.backend.resource_identifiers.target_group_arn
    bucket                   = module.frontend.resource_identifiers.s3_bucket_id
    distribution             = module.frontend.resource_identifiers.cloudfront_distribution_id
    application_url          = "https://${var.public_hostname}"
    repository_url           = module.backend.ecr_repository_url
  })
}

resource "aws_iam_role" "github_deploy" {
  count              = var.github_repository != "" ? 1 : 0
  name               = "${var.project_name}-${var.environment}-github-deploy"
  assume_role_policy = aws_iam_role.github_ecr_push[0].assume_role_policy
}

resource "aws_iam_role_policy" "github_deploy" {
  count = var.github_repository != "" ? 1 : 0
  name  = "deploy-application"
  role  = aws_iam_role.github_deploy[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = aws_ssm_parameter.deployment[0].arn },
      { Effect = "Allow", Action = ["ecs:DescribeServices", "ecs:UpdateService"], Resource = module.backend.resource_identifiers.ecs_service_arn },
      {
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition"]
        Resource = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.github[0].account_id}:task-definition/${module.backend.resource_identifiers.task_definition_family}-release:*"
      },
      { Effect = "Allow", Action = ["ecs:DescribeTaskDefinition"], Resource = "*" },
      {
        Effect    = "Allow"
        Action    = ["ecs:ListTasks", "ecs:DescribeTasks"]
        Resource  = "*"
        Condition = { ArnEquals = { "ecs:cluster" = module.backend.resource_identifiers.ecs_cluster_arn } }
      },
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = module.backend.resource_identifiers.execution_role_arn
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
      { Effect = "Allow", Action = ["elasticloadbalancing:DescribeTargetHealth"], Resource = "*" },
      {
        Effect   = "Allow"
        Action   = ["ecr:DescribeImages"]
        Resource = "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.github[0].account_id}:repository/${var.project_name}"
      },
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = module.frontend.resource_identifiers.s3_bucket_arn },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${module.frontend.resource_identifiers.s3_bucket_arn}/*" },
      {
        Effect   = "Allow"
        Action   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
        Resource = module.frontend.resource_identifiers.cloudfront_distribution_arn
      }
    ]
  })
}

output "github_deployment" {
  description = "GitHub dev environment variables for manually triggered application deployments."
  value = var.github_repository == "" ? null : {
    AWS_DEPLOY_ROLE_ARN       = aws_iam_role.github_deploy[0].arn
    DEPLOYMENT_PARAMETER_NAME = aws_ssm_parameter.deployment[0].name
  }
}
