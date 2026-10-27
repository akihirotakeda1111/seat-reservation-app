output "project_name" {
  description = "Project name"
  value       = var.project_name
}

output "environment" {
  description = "Environment name"
  value       = var.environment
}

output "aws_region" {
  description = "AWS Region"
  value       = var.aws_region
}

output "resource_identifiers" {
  description = "Key resource IDs and ARNs. Excludes the master password value. The secret ARN is a reference, not a secret value."
  value = {
    vpc_id                     = aws_vpc.main.id
    internet_gateway_id        = aws_internet_gateway.main.id
    nat_gateway_id             = aws_nat_gateway.main.id
    nat_eip_allocation_id      = aws_eip.nat.id
    nat_public_ip              = aws_eip.nat.public_ip
    public_subnet_az_a_id      = aws_subnet.public[local.nat_az].id
    private_subnet_az_a_id     = aws_subnet.private[local.nat_az].id
    private_subnet_az_c_id     = aws_subnet.private[local.rds_az].id
    alb_security_group_id      = aws_security_group.alb.id
    ecs_security_group_id      = aws_security_group.ecs.id
    rds_security_group_id      = aws_security_group.rds.id
    alb_arn                    = aws_lb.web.arn
    alb_dns_name               = aws_lb.web.dns_name
    target_group_arn           = aws_lb_target_group.app.arn
    ecs_cluster_arn            = aws_ecs_cluster.app.arn
    ecs_service_name           = aws_ecs_service.app.name
    execution_role_arn         = aws_iam_role.execution.arn
    log_group_name             = aws_cloudwatch_log_group.app.name
    rds_instance_identifier    = aws_db_instance.app.identifier
    rds_address                = aws_db_instance.app.address
    rds_master_user_secret_arn = aws_db_instance.app.master_user_secret[0].secret_arn
  }
}

output "endpoints" {
  description = "ALB DNS name. Does not match the certificate hostname and does not guarantee HTTPS connectivity or application readiness."
  value = {
    alb_dns_name = aws_lb.web.dns_name
  }
}

output "alb" {
  value = { dns_name = aws_lb.web.dns_name, zone_id = aws_lb.web.zone_id }
}
