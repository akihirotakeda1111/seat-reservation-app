# CI/CD設計

## 1. 目的・対象範囲

GitHub Actionsを利用して、座席予約システムのテスト・ビルド・AWSデプロイを自動化する。インフラ変更とアプリケーションデプロイは分離する。

AWS構成とリソース管理境界は[AWSアーキテクチャ設計](aws-architecture.md)を参照する。

本書はGitHub Actionsによる自動化の設計と、現在の手動デプロイの実装を区別して記載する。「手動実装済み」はActionsへの組み込み完了を意味しない。未決定の項目は「未確定」、記載欄は「—」とする。

## 2. 配置・ワークフロー構成

`phase4-dir.md`に基づく配置は以下とする。下記は配置設計であり、各ファイルの存在や実装完了を示すものではない。

```text
doc/
├── system-design/
│   ├── aws-architecture.md
│   └── cicd-design.md
└── adr/
    └── adr-007.md

terraform/
├── bootstrap/
├── tests/
└── README.md

.github/
└── workflows/
    ├── ci.yml
    ├── deploy-backend.yml
    ├── deploy-frontend.yml
    └── terraform.yml
```

| ワークフロー | 責務 |
|---|---|
| `ci.yml` | Backend・Frontendのビルドとテスト |
| `deploy-backend.yml` | Backendイメージのビルド、ECRへのPush、ECSへのデプロイ、稼働確認 |
| `deploy-frontend.yml` | Reactのビルド、S3への配置、CloudFrontキャッシュ無効化 |
| `terraform.yml` | Terraform fmt / validate / plan / applyによるインフラ更新 |

## 3. 実行条件・AWS認証

### 3.1 実行条件

完了条件として、Pull RequestでCIを実行でき、mainへのマージ後にアプリケーションをデプロイできることを求める。CI失敗時はデプロイしない。

具体的なイベント設定、ワークフロー間の連携、Apply承認方法は未確定とする。

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| CIの実行トリガー詳細・対象ブランチ・対象パス | 未確定 | — |
| Backendデプロイの実行トリガー詳細 | 未確定 | — |
| Frontendデプロイの実行トリガー詳細 | 未確定 | — |
| CI成功をデプロイ条件にする具体方式 | 未確定 | — |
| Terraform plan / applyの実行条件 | 未確定 | — |
| Terraform applyの承認方式 | 未確定 | — |

### 3.2 AWS認証・秘密情報

- GitHub ActionsからのAWS認証はGitHub OIDC + IAM Roleを使用する（未実装）。手動実行は対象AWSアカウントへの認証を事前に行う。
- 長期有効なAWSアクセスキーは利用しない。
- デプロイ用IAM Roleには必要最小限の権限を付与する。
- AWSアクセスキーやDBパスワードをリポジトリに保存しない。
- Terraform Stateをリポジトリにコミットしない。

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| OIDCの信頼条件・IAM Role構成・ARN | 未確定 | — |
| 各ワークフローの具体的なIAM権限 | 未確定 | — |
| GitHub Actionsに渡す設定値と保管先 | 未確定 | — |

DB認証情報の管理方式とTerraform State管理方式は[AWSアーキテクチャ設計](aws-architecture.md)を参照する。

## 4. CI設計

CIはGitHub-hosted Runnerで実行する。

### 4.1 Backend

1. JDKをセットアップする。
2. Mavenでビルドする。
3. Unit Testを実行する。
4. TestcontainersによるIntegration Testを実行する。
5. Spring Securityの認証認可テストを実行する。

認証認可テストでは既存のMock JWTを使用し、CI内ではCognitoへの実ログインを行わない。

### 4.2 Frontend

1. Node.jsをセットアップする。
2. 依存関係をインストールする。
3. テストとビルドを実行する。

### 4.3 CI設定

| 設定項目 | 内容 |
|---|---|
| Runner | Ubuntu 24.04 |
| ツール | Temurin JDK 25、Node.js 24、Terraform 1.14.7 |
| Backend | `sh ./mvnw -B -ntp verify`成功後、`backend`で`docker build --tag seat-reservation-app:ci .`を実行。イメージのPush・起動は行わない |
| Frontend | `npm ci`、`npm run format:check`、`npm test`、`npm run build` |
| ブラウザテスト | Chromeをインストールし、`test:e2e`と`test:integration`を別Runnerで実行。各1 worker、`--forbid-only`指定 |
| Terraform | root・bootstrapで`fmt -check -recursive`、`init -backend=false -lockfile=readonly`、`validate`。rootで`test`（AWSモック） |
| 権限 | `contents: read`。AWS認証・Stateへの接続なし |
| 実行上限 | Backend・ブラウザ各20分、Frontend・Terraform各10分 |
| 診断情報 | Surefireレポート、Playwrightの失敗時trace・結合テストAPIログをArtifactsに7日間保存 |

## 5. アプリケーションCD設計

### 5.1 Backend

```text
GitHub Actions
    ↓
AWS OIDC認証
    ↓
Docker Build
    ↓
ECR Push
    ↓
ECS Task Definition更新
    ↓
ECS Service更新
    ↓
稼働状態確認
```

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| コンテナイメージのタグ・バージョン管理方式 | 確定 | タグは変更不可。デプロイはdigestで固定 |
| ECS Task Definitionの更新方法 | 初期実装 | Terraformで管理。Actionsによる直接更新導入時は管理境界を変更 |
| ECSへのアプリケーション設定の注入方法 | 確定 | DB接続先・Cognitoは環境変数、DB認証情報はSecrets Managerから起動時に注入 |
| 手動デプロイの稼働確認・失敗判定 | 手動実装済み | Terraformの`wait_for_steady_state = true`で安定状態を待機。起動猶予は既定300秒。サーキットブレーカーで失敗を検出し、意図したdigestとALBのHealthyは別途確認 |
| Actionsでの待機時間・失敗判定・再試行 | 未確定 | — |

上図は将来のActionsによる処理を示す。現在はDockerでビルド・Pushし、`terraform.tfvars`のdigestを更新してplan・applyする。手動手順のタグはコミットIDとUTC日時を組み合わせる。ECSは更新時に旧タスクを停止してから新タスクを起動するため、停止時間が発生する。

### 5.2 Frontend

```text
GitHub Actions
    ↓
React Build
    ↓
S3 Upload
    ↓
CloudFront Invalidation
```

上図は将来のActionsによる処理を示す。現在は手動で以下の設定・操作を実施する。Actionsへの設定値の受け渡し・保管方法は3.2節の未確定事項として扱う。

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| 手動ビルド時の環境設定 | 手動実装済み | `frontend/.env.production.local`にCognito Authority・Client ID・Domain、Redirect URI・Logout URIを設定。`npm --prefix frontend ci`と`npm --prefix frontend run build`でビルド |
| 手動S3配置・古いファイルの扱い | 手動実装済み | `frontend/dist`を対象バケットへ`aws s3 sync --delete`で同期 |
| 手動CloudFrontキャッシュ無効化 | 手動実装済み | 配置後に`/*`を無効化し、完了を待機 |
| Actionsへの上記手順の組み込み | 未実装 | 設定値の受け渡し・実行条件を決定後に実装 |
| ビルド成果物の保存・世代管理方法 | 未確定 | — |

環境変数はビルド時に埋め込まれるため、変更後は再ビルド・再配置する。現在のS3同期は以前のビルド成果物を保存する仕組みではなく、復旧用の世代管理は未実装。

## 6. インフラ更新・削除

自動化ではインフラ更新をアプリケーション更新から分離する方針とする。現在はBackendの更新にもTerraformを使用し、以下の順序で手動実行する。

```text
Terraform fmt / validate
    ↓
Terraform plan
    ↓
Terraform apply
```

Terraform State保存先は`terraform/bootstrap`で一時環境から独立したS3として構築する。GitHub Actionsからのアクセス権限はOIDC Role実装時に整備する。構築した環境は検証後にTerraform destroyで削除する。既存Cognito、State保存先、手動管理のRoute 53・ACMは削除対象外とする。

初回はECRを含むリソース全体を作成し、ECS Serviceを0タスクにする。イメージPush後にdigest・Cognito設定を渡し、1タスクへ変更する。現在のTerraform実装はタスク定義とタスク数も管理するため、ActionsからECSを直接更新する前に二重管理を解消する。OIDC Role・Workflowの実装は別工程とする。具体的な手動手順は[Terraform README](../../terraform/README.md)を参照する。

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| Terraform実行バージョン・具体的な実行コマンド | 手動実装済み | 1.14.7以上1.15.0未満。Terraform README参照 |
| plan結果の確認・applyへの受け渡し方法 | 初期実装 | planをファイルに保存し、確認したファイルをapply |
| 初回のインフラ構築とアプリケーションデプロイの連携手順 | 確定 | 全リソース構築（0タスク）→Push→digest指定・1タスクへ変更 |
| 手動destroyの実行方法 | 手動実装済み | リポジトリルートから`terraform -chdir=terraform plan -destroy -out=destroy.tfplan`を実行し、確認した計画をapply。bootstrapは破棄しない |
| Actionsでのdestroy実行条件・承認方式 | 未確定 | — |

## 7. DBマイグレーション

- 初期構成ではSpring Boot起動時のFlywayマイグレーションを継続する。
- DBへのアクセスはECSから行う。
- GitHub ActionsからPrivate RDSへ直接接続しない。
- DBマイグレーションは原則として前方互換にし、アプリケーションのロールバック時にも動作するようにする。
- Build & ScrapによってRDSの予約データが失われることを許容する。

## 8. デプロイ後検証

| 検証対象 | 確認内容 |
|---|---|
| ECS Service | 安定状態であること |
| ALB Target Group | TargetがHealthyであること |
| Frontend | CloudFront経由で画面が表示されること |
| API | CloudFront経由で疎通を確認できること |
| 認証・業務操作 | Cognito実認証による予約・キャンセル操作を手動で確認すること |

Cognito実認証を伴うE2EはCIとは別途実施する。実認証E2EのCI自動化はPhase 4の必須範囲に含めない。

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| ALBヘルスチェックのパス・判定条件 | 実装済み | HTTP:8080の`/actuator/health`が200。起動猶予は`health_check_grace_period_seconds`で指定（既定300秒） |
| 手動でのFrontend・API疎通確認 | 手順確定 | Terraformの`application_url`を開き、画面表示とログイン後の予約・キャンセルが成功することを確認 |
| Cognito実認証による基本操作確認 | 手動確認済み | 利用者から正常な表示・操作の報告あり。全ロール・異常系の網羅的検証完了を意味しない |
| Actionsでの疎通確認URL・成功条件・自動化範囲 | 未確定 | — |

## 9. 障害・失敗時の対応

Actions導入時はCI失敗時にデプロイしない。現在のECS復旧手順と、今後決定する復旧方針を以下に示す。

| 設計項目 | 状態 | 記載欄 |
|---|---|---|
| ECSデプロイ失敗時のロールバック方法 | 初期実装 | Deployment Circuit Breakerで失敗を検出。初回は`rollback_enabled = false`とし、実イメージでの正常デプロイ確認後にtrueへ変更する |
| 失敗したECSデプロイの手動再試行 | 手順確定 | イベント・アプリログ・ターゲット状態を調査し、原因修正とタスク定義確認後に`--force-new-deployment`で再試行。詳細は調査手順を参照 |
| Frontendの以前のビルド成果物への復旧方法 | 未確定 | — |
| DBマイグレーション失敗時の対応手順 | 未確定 | — |
| インフラ更新失敗時の復旧方法 | 未確定 | — |

Terraformは正常デプロイの履歴を自動判定しない。初回の実イメージ稼働を確認してからロールバックを有効化する。Terraformの待機タイムアウトはAWS側の変更を取り消さず、起動猶予だけの変更では新しいデプロイは開始されない。
