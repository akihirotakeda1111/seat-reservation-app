# Seat Reservation System

Java 25 / Spring Boot 4.1.xを使用した座席予約システム。

既存のJava実務経験を基盤に、Modern Java / Backend / AWSへの技術領域拡張を目的として開発する。

## 目的

座席予約を題材に、以下の技術領域を実践する。

- **Modern Java**：Java 25 / Spring BootによるREST API開発
- **Transaction / Concurrency**：PostgreSQLを用いたデータ整合性と同時予約の制御
- **Authentication / Authorization**：Spring Security / Amazon Cognitoによる認証認可
- **AWS / IaC**：ECS/Fargate、RDS、Terraformによるクラウド環境構築
- **Testing / CI/CD**：Integration Test、GitHub Actionsによる自動検証・デプロイ

## 機能概要

座席マスタに登録した座席を日付ごとに予約するシステム。初期構成は30席とする。

- 日付別の座席状況確認
- 空席の予約と自分の予約のキャンセル
- 空席・自席・他ユーザー予約席の区別
- Pollingによる座席状況の更新
- ユーザー登録・ログイン、管理者による予約管理（Phase 3）

## アーキテクチャ

### Phase 1：ローカル環境

Browser（React / Vite） → Spring Boot API（Java 25） → PostgreSQL

- Backend：Spring Boot / Spring Data JPA
- Database：PostgreSQL / Flyway
- Local Environment：Docker Compose
- Testing：JUnit / Testcontainers

### 最終構成（予定）

- Frontend：S3 + CloudFront
- Backend：ALB + ECS/Fargate（Spring Boot）
- Database：RDS PostgreSQL
- Authentication：Amazon Cognito + Spring Security
- Infrastructure：Terraform
- CI/CD：GitHub Actions + GitHub OIDC

## 開発ロードマップ

| Phase             | 内容                                                |
| ----------------- | --------------------------------------------------- |
| 1. MVP            | ローカル環境で座席照会・予約・キャンセルを実装      |
| 2. Concurrency    | 同時予約制御、Transaction設計、並列Integration Test |
| 3. Authentication | Cognito / JWT / Spring Securityによる認証認可       |
| 4. AWS            | TerraformによるAWS環境構築とCI/CD                   |

## 開発方針

過剰設計を避け、各Phaseで必要な機能を段階的に実装する。

要件・設計・技術判断は自身で行い、AIは実装補助・調査・レビューに限定して利用する。

主要な設計判断はADR（Architecture Decision Record）として記録する。
