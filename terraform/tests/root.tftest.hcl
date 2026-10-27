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

run "root_bootstrap" {
  command = plan
  assert {
    condition     = output.application_url == "https://app.example.com"
    error_message = "Public URL must use the supplied hostname."
  }
  assert {
    condition     = output.dns_records.public.name == var.public_hostname && output.dns_records.origin.name == var.origin_hostname && output.dns_records.public.types == tolist(["A", "AAAA"]) && output.dns_records.origin.types == tolist(["A"])
    error_message = "Manual DNS instructions must include the public and origin names and record types."
  }
}

run "root_running" {
  command = plan
  variables {
    desired_count      = 1
    image_digest       = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_example"
    cognito_client_id  = "example"
  }
}

run "reject_public_url_instead_of_hostname" {
  command = plan
  variables { public_hostname = "https://app.example.com" }
  expect_failures = [var.public_hostname]
}
run "reject_origin_wildcard" {
  command = plan
  variables { origin_hostname = "*.example.com" }
  expect_failures = [var.origin_hostname]
}
run "reject_same_hostnames" {
  command = plan
  variables { origin_hostname = "app.example.com" }
  expect_failures = [var.origin_hostname]
}
run "reject_cloudfront_certificate_region" {
  command = plan
  variables { cloudfront_certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000001" }
  expect_failures = [var.cloudfront_certificate_arn]
}
run "reject_alb_certificate_region" {
  command = plan
  variables { alb_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000002" }
  expect_failures = [var.alb_certificate_arn]
}
run "reject_malformed_certificate_arn" {
  command = plan
  variables { alb_certificate_arn = "not-an-arn" }
  expect_failures = [var.alb_certificate_arn]
}

run "reject_unsupported_environment" {
  command = plan
  variables { environment = "prod" }
  expect_failures = [var.environment]
}
run "reject_unsupported_region" {
  command = plan
  variables {
    aws_region          = "us-west-2"
    alb_certificate_arn = "arn:aws:acm:us-west-2:123456789012:certificate/00000000-0000-0000-0000-000000000002"
  }
  expect_failures = [var.aws_region]
}
run "reject_multiple_tasks" {
  command = plan
  variables {
    desired_count      = 2
    image_digest       = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_example"
    cognito_client_id  = "example"
  }
  expect_failures = [var.desired_count]
}