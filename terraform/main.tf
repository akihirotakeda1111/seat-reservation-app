module "backend" {
  source          = "./container-web"
  project_name    = var.project_name
  environment     = var.environment
  aws_region      = var.aws_region
  certificate_arn = var.alb_certificate_arn

  application = {
    image_digest       = var.image_digest
    cognito_issuer_uri = var.cognito_issuer_uri
    cognito_client_id  = var.cognito_client_id
  }
  deployment = {
    desired_count                     = var.desired_count
    rollback_enabled                  = var.rollback_enabled
    cpu                               = var.task_cpu
    memory                            = var.task_memory
    health_check_grace_period_seconds = var.health_check_grace_period_seconds
  }
}

module "frontend" {
  source       = "./static-site"
  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  routing = {
    hostname        = var.public_hostname
    certificate_arn = var.cloudfront_certificate_arn
    api_hostname    = var.origin_hostname
    vpc_origin_id   = module.backend.vpc_origin_id
  }
}