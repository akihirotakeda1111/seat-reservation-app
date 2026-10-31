# AWS・CI/CDの障害調査と復旧

通常の操作は[Terraform README](README.md)・[CI/CD設定](../.github/CICD.md)を参照。以下のコマンドはLinux / Bashで、リポジトリルートから実行する。Terraform・AWS CLI・jqを用意し、対象AWSアカウントへ認証してから各コマンドの成功を確認して進む。

## CI・CDの失敗を確認する

| 状況 | 確認先・対応 |
| --- | --- |
| CI・成果物準備の失敗 | Actionsの失敗ステップを確認し、原因修正後にCIを再実行。片方の成果物だけ成功していてもデプロイしない |
| デプロイのスキップ | 実行ブランチがmain、Repository Variableの`DEPLOY_ENABLED`が`true`か確認 |
| AWS認証・事前チェックの失敗 | 該当ステップのログを確認。認証では`dev`の`AWS_DEPLOY_ROLE_ARN`とOIDC信頼条件、SSM取得では`DEPLOYMENT_PARAMETER_NAME`を確認 |
| デプロイ処理の失敗 | 成果物取得・AWS認証・事前チェック・配置・公開確認が記録対象。`deployment-*` Artifact・Summaryの`stage`・`status`・エラー概要を確認し、詳細は失敗ステップのログで調べる。Artifactの保持期間は30日 |

CI実行ID・コミット・以前／今回のタスク定義などは、取得できた情報から順次記録する。checkout失敗、キャンセル、ジョブのタイムアウトなどでは、Artifact・Summaryが残らない場合がある。

CDが失敗・タイムアウトしても、AWS側で処理が続いている場合がある。再実行前にECSのデプロイ状態を確認する。

## デプロイの再試行・ロールバック

1. イベント・アプリログ・ターゲット状態から原因を特定し、修正する。
2. 進行中のECSデプロイ終了を待つ。Terraformで基本設定を修正する場合は`DEPLOY_ENABLED=false`にし、実行中・待機中のCDがない状態でplan・applyする。
3. `DEPLOY_ENABLED=true`に戻し、Actions → **Deploy release**でmainと同じ`release_run_id`を指定する。アプリやFrontend公開設定を変更した場合はCIから実行し、新しいCI実行IDを指定する。

| 対象 | 復旧方針 |
| --- | --- |
| Backend | Deployment Circuit Breakerで失敗を検出。`rollback_enabled=true`なら以前の正常完了デプロイへ戻す。CDは失敗扱いとし、Frontend更新へ進まない |
| 初回起動 | 正常稼働していたアプリの復旧先がないため、`rollback_enabled=false`を維持。実イメージでの正常稼働確認後にTerraformでtrueへ変更する。Terraformは正常稼働の履歴を自動判定しない |
| Frontend・公開URL検証 | 全体の自動ロールバックは行わず、Backendのみ更新済みの場合がある。原因修正後のCD再実行、または以前のアプリへの復旧を行う |
| 以前のアプリ | 以前の成功CI実行IDでCDを実行。現在の基本設定と以前のイメージを組み合わせるため、設定・DBとの互換性を確認する。GitHub上に両Artifactsが残っていることが必要 |
| DB | マイグレーションを自動で巻き戻さない。アプリのロールバックだけでDBが復元されるとは扱わない |

リリースArtifactsの保持期間は30日。期限切れ後の手元保存分は現在のCDに取り込めず、別途手動復旧が必要。復旧後はCDの正常性確認に加え、公開URLでログイン・予約・キャンセルを確認する。

## DNSが解決できない

ドメイン取得元からRoute 53へのNS委任を確認する。修正後も失敗する場合は、対象ホストゾーンのNSへ直接問い合わせてキャッシュと設定を切り分ける（`dig`が必要）。

```bash
app_domain='＜公開FQDN＞'
route53_ns='＜対象ホストゾーンのNSレコードにあるサーバーを1つ＞'
dig @"$route53_ns" "$app_domain" A
dig @1.1.1.1 "$app_domain" A
dig "$app_domain" A
```

## ECSのヘルスチェック失敗・Terraformの待機タイムアウト

CDはECS更新後、対象デプロイが参照結果に現れるまで最大60秒待機する。`ECS update was not observed within 60 seconds`で失敗した場合も、以下のコマンドでECSの実状態を確認してから再実行する。この反映待ちはECS待機上限20分に含む。

CD・Terraformの待機タイムアウトはAWS側の処理を取り消さない。ECSサービスのイベント・停止タスクの理由・CloudWatchのアプリログを確認する。終了コード143は通常停止要求による終了であり、原因はその前のヘルスチェックや起動ログから調べる。起動猶予の既定値は300秒。

```bash
backend_outputs="$(terraform -chdir=terraform output -json backend)"
cluster="$(printf '%s' "$backend_outputs" | jq -er '.ecs_cluster_arn')"
service="$(printf '%s' "$backend_outputs" | jq -er '.ecs_service_name')"
target_group="$(printf '%s' "$backend_outputs" | jq -er '.target_group_arn')"
```

各値の取得成功を確認してから実行する。

```bash
aws ecs describe-services --region ap-northeast-1 \
    --cluster "$cluster" --services "$service" \
    --query 'services[0].{Desired:desiredCount,Running:runningCount,Pending:pendingCount,TaskDefinition:taskDefinition,Grace:healthCheckGracePeriodSeconds,Deployments:deployments,Events:events[:10]}' \
    --output json
aws elbv2 describe-target-health --region ap-northeast-1 \
    --target-group-arn "$target_group" --output json
```

ターゲットが空の場合は登録解除済みの可能性がある。再試行中に取得し、`Reason`と`Description`を確認する。旧設定や初回起動前の誤ったrollback有効化で`:bootstrap`に戻ると、存在しないイメージの取得に失敗する。起動するタスク定義が意図した`@sha256:...`を参照しているか確認する。初回起動の再試行では`rollback_enabled = false`を維持し、失敗原因を修正してから再デプロイする。

```bash
task_definition="$(aws ecs describe-services --region ap-northeast-1 \
    --cluster "$cluster" --services "$service" \
    --query 'services[0].taskDefinition' --output text)"
aws ecs describe-task-definition --region ap-northeast-1 \
    --task-definition "$task_definition" \
    --query 'taskDefinition.containerDefinitions[].{Name:name,Image:image}' --output table
```

通常の再試行は前述のCD再実行を使用する。調査のため同じタスク定義だけを再起動する場合は、CDを無効にして進行中・待機中の実行がないことを確認し、Terraform実行終了後に以下を実行する。イメージ・タスク数は変更せず、基本定義の再登録・Frontend配置も行わない。起動猶予だけの変更では新しいデプロイは開始されない。

```bash
aws ecs update-service --region ap-northeast-1 \
    --cluster "$cluster" --service "$service" --force-new-deployment \
    --query 'service.deployments[].{ID:id,State:rolloutState,CreatedAt:createdAt}' \
    --output table
```

復旧後はECSの稼働定義・digest・ALB Healthyを確認する。インフラを変更した場合はTerraform planも確認するが、`ignore_changes`対象の稼働定義・タスク数はplanでは検証できない。

## Terraform更新・破棄が失敗した場合

CDを無効にし、進行中・待機中の実行がないことを確認する。破棄時はBackend成果物準備も停止する。エラーとAWS側の状態を確認し、原因解消後に新しいplan（破棄時はdestroy plan）を作成して内容を確認する。リソースごとの復旧判断は、以下の「今後の検討事項」とする。

未削除のリソースをStateから外して済ませず、State保存先とbootstrapのローカルStateを保持する。破棄の対象・残るリソースは[Terraform README](README.md#4-環境の破棄)を参照する。

## 今後の検討事項

以下は現時点の対応範囲に含めず、復旧要件が必要になった段階で方針・手順を検討する。

| 項目 | 検討内容 |
| --- | --- |
| DBマイグレーション失敗時の復旧 | データ保持方針、バックアップ・復元方法、修復手順 |
| インフラ更新失敗時の復旧 | リソース別の復旧判断、Stateとの整合、再構築への切り替え条件 |
