resource "aws_db_subnet_group" "app" {
  name        = local.db_identifier
  description = "Private DB subnets for PostgreSQL"
  subnet_ids  = [for az in local.azs : aws_subnet.private[az].id]
}

resource "aws_db_parameter_group" "app" {
  name        = local.db_identifier
  family      = "postgres17"
  description = "PostgreSQL 17 with TLS required"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "app" {
  identifier     = local.db_identifier
  engine         = "postgres"
  engine_version = "17.11"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "app"
  username = "postgres"
  port     = 5432

  manage_master_user_password = true

  db_subnet_group_name                = aws_db_subnet_group.app.name
  parameter_group_name                = aws_db_parameter_group.app.name
  vpc_security_group_ids              = [aws_security_group.rds.id]
  publicly_accessible                 = false
  multi_az                            = false
  availability_zone                   = local.rds_az
  backup_retention_period             = 0
  backup_window                       = "18:00-19:00"
  maintenance_window                  = "sun:19:30-sun:20:30"
  copy_tags_to_snapshot               = true
  deletion_protection                 = false
  skip_final_snapshot                 = true
  delete_automated_backups            = true
  auto_minor_version_upgrade          = false
  performance_insights_enabled        = false
  monitoring_interval                 = 0
  iam_database_authentication_enabled = false
  apply_immediately                   = false
}
