output "ecr_repository_url" {
  value = module.backend.ecr_repository_url
}
output "backend" {
  value = module.backend.resource_identifiers
}
output "frontend" {
  value = module.frontend.resource_identifiers
}
output "application_url" {
  value = "https://${var.public_hostname}"
}
output "dns_records" {
  description = "Manually create Route 53 alias records using these target names and target hosted zone IDs."
  value = {
    public = {
      name                   = var.public_hostname
      types                  = tolist(["A", "AAAA"])
      alias_target           = module.frontend.distribution.domain_name
      alias_zone_id          = module.frontend.distribution.zone_id
      evaluate_target_health = false
    }
    origin = {
      name                   = var.origin_hostname
      types                  = tolist(["A"])
      alias_target           = module.backend.alb.dns_name
      alias_zone_id          = module.backend.alb.zone_id
      evaluate_target_health = false
    }
  }
}
