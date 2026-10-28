mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-1a", "ap-northeast-1c"] }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}
variables {
  cloudfront_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000001"
  alb_certificate_arn        = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000002"
  public_hostname            = "app.example.com"
  origin_hostname            = "origin.example.com"
}

run "disabled_by_default" {
  command = plan
  assert {
    condition     = length(aws_iam_role.github_ecr_push) == 0 && length(aws_iam_openid_connect_provider.github) == 0
    error_message = "Do not create GitHub access until a repository is configured."
  }
}

run "reuse_provider_and_restrict_push" {
  command = plan
  variables {
    github_repository        = "example/seat-reservation-app"
    github_owner_id          = "123456"
    github_repository_id     = "789012"
    github_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  }
  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0 && jsondecode(aws_iam_role.github_ecr_push[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] == "repo:example@123456/seat-reservation-app@789012:environment:dev" && jsondecode(aws_iam_role.github_ecr_push[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Reuse the shared provider and restrict trust to the repository dev environment."
  }
  assert {
    condition     = jsondecode(aws_iam_role_policy.github_ecr_push[0].policy).Statement[1].Resource == "arn:aws:ecr:ap-northeast-1:123456789012:repository/seat-reservation-app" && toset(jsondecode(aws_iam_role_policy.github_ecr_push[0].policy).Statement[1].Action) == toset(["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:DescribeImages"])
    error_message = "Allow only ECR upload and digest lookup on the application repository."
  }
}

run "create_provider_when_requested" {
  command = plan
  variables {
    github_repository    = "example/seat-reservation-app"
    github_owner_id      = "123456"
    github_repository_id = "789012"
  }
  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 1 && aws_iam_openid_connect_provider.github[0].url == "https://token.actions.githubusercontent.com" && aws_iam_openid_connect_provider.github[0].client_id_list == toset(["sts.amazonaws.com"])
    error_message = "Create the GitHub OIDC provider with the STS audience."
  }
}

run "reject_missing_ids" {
  command = plan
  variables { github_repository = "example/seat-reservation-app" }
  expect_failures = [var.github_owner_id, var.github_repository_id]
}

run "reject_nonnumeric_ids" {
  command = plan
  variables {
    github_repository    = "example/seat-reservation-app"
    github_owner_id      = "example"
    github_repository_id = "789012*"
  }
  expect_failures = [var.github_owner_id, var.github_repository_id]
}
