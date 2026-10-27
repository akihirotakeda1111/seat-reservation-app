mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-1a", "ap-northeast-1c"] }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}

run "bootstrap_without_image" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name = "seat-reservation-app"
    environment  = "dev"
    aws_region   = "ap-northeast-1"

    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
  }
  assert {
    condition     = aws_ecs_service.app.desired_count == 0 && aws_ecs_service.app.wait_for_steady_state
    error_message = "Bootstrap must not attempt to pull an image."
  }
  assert {
    condition     = aws_ecs_service.app.deployment_circuit_breaker[0].enable && !aws_ecs_service.app.deployment_circuit_breaker[0].rollback
    error_message = "Bootstrap must detect failures without rolling back to a placeholder image."
  }
  assert {
    condition     = aws_lb.web.internal && !aws_ecs_service.app.network_configuration[0].assign_public_ip && !aws_db_instance.app.publicly_accessible
    error_message = "ALB, ECS, and RDS must remain private."
  }
  assert {
    condition     = length(aws_subnet.public) == 1 && length(aws_subnet.private) == 2
    error_message = "Use the minimal 1 public / 2 private subnet layout."
  }
  assert {
    condition     = !aws_db_instance.app.deletion_protection && aws_db_instance.app.skip_final_snapshot && aws_db_instance.app.delete_automated_backups && aws_ecr_repository.app.force_delete
    error_message = "Temporary data must follow the Build & Scrap policy."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.alb_https.cidr_ipv4 == null && aws_vpc_security_group_egress_rule.ecs_https.cidr_ipv4 == "0.0.0.0/0"
    error_message = "ALB ingress must be SG-limited while ECS can reach public HTTPS through NAT."
  }
}

run "reject_start_without_image" {
  command = plan
  module { source = "./container-web" }
  variables {
    deployment   = { desired_count = 1 }
    project_name = "seat-reservation-app"
    environment  = "dev"
    aws_region   = "ap-northeast-1"

    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    application = {

      cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_example"
      cognito_client_id  = "example"
    }
  }
  expect_failures = [var.application]
}

run "start_with_image_and_cognito" {
  command = plan
  module { source = "./container-web" }
  variables {
    deployment   = { desired_count = 1 }
    project_name = "seat-reservation-app"
    environment  = "dev"
    aws_region   = "ap-northeast-1"

    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    application = {

      image_digest       = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
      cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_example"
      cognito_client_id  = "example"
    }
  }
  assert {
    condition     = aws_ecs_service.app.desired_count == 1 && aws_ecs_service.app.wait_for_steady_state
    error_message = "A configured deployment must start one task."
  }
  assert {
    condition     = !aws_ecs_service.app.deployment_circuit_breaker[0].rollback
    error_message = "The first real-image deployment must not enable rollback automatically."
  }
}

run "frontend_routes_api_without_spa_fallback" {
  command = plan
  module { source = "./static-site" }
  variables {
    project_name = "seat-reservation-app"
    environment  = "dev"
    aws_region   = "ap-northeast-1"

    routing = {
      hostname        = "app.example.com"
      certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
      api_hostname    = "origin.example.com"
      vpc_origin_id   = "vo_example"
    }
  }
  assert {
    condition     = length(aws_cloudfront_distribution.site.ordered_cache_behavior) == 2 && alltrue([for b in aws_cloudfront_distribution.site.ordered_cache_behavior : b.target_origin_id == "api" && length(b.function_association) == 0])
    error_message = "API paths must bypass S3 and SPA rewriting."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.site.custom_error_response) == 11 && alltrue([for e in aws_cloudfront_distribution.site.custom_error_response : e.error_caching_min_ttl == 0 && (e.response_code == null || e.response_code == 0) && (e.response_page_path == null || e.response_page_path == "")]) && aws_s3_bucket.site.force_destroy
    error_message = "Do not mask API errors; allow deletion of temporary frontend artifacts."
  }
}

run "normal_deployment_with_rollback_and_custom_size" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name    = "seat-reservation-app"
    environment     = "dev"
    aws_region      = "ap-northeast-1"
    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    application = {
      image_digest       = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
      cognito_issuer_uri = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_example"
      cognito_client_id  = "example"
    }
    deployment = {
      desired_count                     = 1
      rollback_enabled                  = true
      cpu                               = 512
      memory                            = 2048
      health_check_grace_period_seconds = 600
    }
  }
  assert {
    condition     = aws_ecs_service.app.deployment_circuit_breaker[0].enable && aws_ecs_service.app.deployment_circuit_breaker[0].rollback
    error_message = "Normal deployments must honor explicit rollback opt-in."
  }
  assert {
    condition     = aws_ecs_task_definition.app.cpu == "512" && aws_ecs_task_definition.app.memory == "2048" && aws_ecs_service.app.health_check_grace_period_seconds == 600
    error_message = "Task sizing and startup grace must use deployment inputs."
  }
}
run "reject_bootstrap_rollback" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name    = "seat-reservation-app"
    environment     = "dev"
    aws_region      = "ap-northeast-1"
    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    deployment      = { rollback_enabled = true }
  }
  expect_failures = [var.application]
}

run "reject_invalid_task_size" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name    = "seat-reservation-app"
    environment     = "dev"
    aws_region      = "ap-northeast-1"
    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    deployment      = { cpu = 256, memory = 4096 }
  }
  expect_failures = [var.deployment]
}

run "reject_fractional_grace" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name    = "seat-reservation-app"
    environment     = "dev"
    aws_region      = "ap-northeast-1"
    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    deployment      = { health_check_grace_period_seconds = 0.5 }
  }
  expect_failures = [var.deployment]
}

run "reject_malformed_digest" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name    = "seat-reservation-app"
    environment     = "dev"
    aws_region      = "ap-northeast-1"
    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    application     = { image_digest = "latest" }
  }
  expect_failures = [var.application]
}

run "reject_missing_cognito" {
  command = plan
  module { source = "./container-web" }
  variables {
    project_name    = "seat-reservation-app"
    environment     = "dev"
    aws_region      = "ap-northeast-1"
    certificate_arn = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    deployment      = { desired_count = 1 }
    application     = { image_digest = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" }
  }
  expect_failures = [var.application]
}
