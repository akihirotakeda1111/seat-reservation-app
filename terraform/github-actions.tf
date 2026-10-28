variable "github_repository" {
  description = "GitHub owner/repository allowed to push images. Empty disables GitHub Actions IAM resources."
  type        = string
  default     = ""
  nullable    = false
  validation {
    condition     = var.github_repository == "" || can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "Use owner/repository, or leave empty to disable GitHub Actions access."
  }
}

variable "github_owner_id" {
  description = "Immutable numeric GitHub owner ID. Required when github_repository is set."
  type        = string
  default     = ""
  nullable    = false
  validation {
    condition     = var.github_repository == "" || can(regex("^[1-9][0-9]*$", var.github_owner_id))
    error_message = "Set github_owner_id to the numeric GitHub owner ID when GitHub Actions access is enabled."
  }
}

variable "github_repository_id" {
  description = "Immutable numeric GitHub repository ID. Required when github_repository is set."
  type        = string
  default     = ""
  nullable    = false
  validation {
    condition     = var.github_repository == "" || can(regex("^[1-9][0-9]*$", var.github_repository_id))
    error_message = "Set github_repository_id to the numeric GitHub repository ID when GitHub Actions access is enabled."
  }
}

variable "github_oidc_provider_arn" {
  description = "Existing account-wide GitHub OIDC provider ARN. Null creates a provider managed by this environment."
  type        = string
  default     = null
  validation {
    condition     = var.github_oidc_provider_arn == null || can(regex("^arn:aws:iam::[0-9]{12}:oidc-provider/token.actions.githubusercontent.com$", var.github_oidc_provider_arn))
    error_message = "Use a GitHub OIDC provider ARN."
  }
}

data "aws_caller_identity" "github" {
  count = var.github_repository != "" ? 1 : 0
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.github_repository != "" && var.github_oidc_provider_arn == null ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "github_ecr_push" {
  count = var.github_repository != "" ? 1 : 0
  name  = "${var.project_name}-${var.environment}-github-ecr-push"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "sts:AssumeRoleWithWebIdentity"
      Principal = {
        Federated = var.github_oidc_provider_arn != null ? var.github_oidc_provider_arn : aws_iam_openid_connect_provider.github[0].arn
      }
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${split("/", var.github_repository)[0]}@${var.github_owner_id}/${split("/", var.github_repository)[1]}@${var.github_repository_id}:environment:dev"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_ecr_push" {
  count = var.github_repository != "" ? 1 : 0
  role  = aws_iam_role.github_ecr_push[0].id
  name  = "push-application-image"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage",
          "ecr:DescribeImages"
        ]
        Resource = "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.github[0].account_id}:repository/${var.project_name}"
      }
    ]
  })
}

output "github_ecr_push_role_arn" {
  description = "Set as AWS_ECR_PUSH_ROLE_ARN in the GitHub dev environment."
  value       = try(aws_iam_role.github_ecr_push[0].arn, null)
}
