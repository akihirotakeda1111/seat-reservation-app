import { AdminReservationFilters } from '../components/AdminReservationFilters';
import { AdminReservationTable } from '../components/AdminReservationTable';
import { CancelReservationDialog } from '../components/CancelReservationDialog';
import { ResultMessage } from '../components/ResultMessage';
import { useAdminReservations } from '../hooks/useAdminReservations';
interface Props {
  accessToken: string;
  onUnauthorized: () => void;
  onLogout: () => void;
  onReservations: () => void;
}
export function AdminReservationPage({
  accessToken,
  onUnauthorized,
  onLogout,
  onReservations,
}: Props) {
  const model = useAdminReservations(accessToken, onUnauthorized);
  return (
    <div className="reservation-app">
      <header className="app-header">
        <h1>
          <small className="admin-badge">管理者</small> 予約管理
        </h1>
        <div className="header-actions">
          <button className="button button-secondary" onClick={onReservations}>
            予約画面へ
          </button>
          <button className="button button-danger" onClick={onLogout}>
            ログアウト
          </button>
        </div>
      </header>
      <main>
        <p>指定日の予約を確認・キャンセルできます。</p>
        <AdminReservationFilters
          date={model.date}
          loading={model.loading}
          disabled={model.cancelling}
          onDateChange={model.changeDate}
          onFetch={model.fetchReservations}
        />
        <AdminReservationTable
          {...model}
          disabled={model.cancelling || model.fetchError}
          onCancel={model.openCancelDialog}
        />
        <ResultMessage
          notice={model.notice}
          fetchError={model.fetchError}
          loading={model.loading}
          onRetry={model.fetchReservations}
          emptyText={
            model.loaded
              ? model.past
                ? '過去日の予約は閲覧のみです。'
                : '取消対象の予約を選択してください。'
              : '利用日を選択して予約一覧を取得してください。'
          }
          fetchErrorText="最新の予約一覧を取得できません。"
        />
        {model.cancelTarget && (
          <CancelReservationDialog
            date={model.date}
            reservation={model.cancelTarget}
            busy={model.cancelling}
            onClose={model.closeCancelDialog}
            onConfirm={model.confirmCancellation}
          />
        )}
      </main>
    </div>
  );
}
