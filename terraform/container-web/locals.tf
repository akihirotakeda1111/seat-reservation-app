locals {
  name_prefix = "${var.project_name}-${var.environment}"

  compact       = replace(local.name_prefix, "-", "")
  name_hash     = substr(sha256(local.name_prefix), 0, 8)
  alb_name      = "alb-${substr(local.compact, 0, 16)}-${local.name_hash}"
  tg_name       = "tg-${substr(local.compact, 0, 17)}-${substr(local.name_hash, 0, 6)}"
  db_identifier = "pg-${local.compact}-${local.name_hash}"

  azs    = slice(sort(data.aws_availability_zones.available.names), 0, 2)
  nat_az = local.azs[0]
  rds_az = local.azs[1]

  vpc_cidr = "10.0.0.0/16"
  public_subnet_cidrs = {
    (local.azs[0]) = "10.0.0.0/24"
  }
  private_subnet_cidrs = {
    (local.azs[0]) = "10.0.10.0/24"
    (local.azs[1]) = "10.0.11.0/24"
  }

  dns_resolver_cidrs = [
    "${cidrhost(local.vpc_cidr, 2)}/32",
    "169.254.169.253/32",
  ]
}

data "aws_availability_zones" "available" {
  state            = "available"
  exclude_zone_ids = ["apne1-az3"]
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}
