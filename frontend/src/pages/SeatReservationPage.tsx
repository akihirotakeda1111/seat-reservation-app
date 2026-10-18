import { ReservationFilters } from '../components/ReservationFilters';
import { SeatMap } from '../components/SeatMap';
import { ReservationActionPanel } from '../components/ReservationActionPanel';
import { ResultMessage } from '../components/ResultMessage';
import { useSeatReservation } from '../hooks/useSeatReservation';
import { formatTime, validDate } from '../utils/jaDate';

interface Props {
  accessToken: string;
  onUnauthorized: () => void;
  onLogout: () => void;
  onAdmin?: () => void;
}

export function SeatReservationPage({
  accessToken,
  onUnauthorized,
  onLogout,
  onAdmin,
}: Props) {
  const model = useSeatReservation(accessToken, onUnauthorized);
  const syncStatus = model.submitting
    ? '処理中'
    : !validDate(model.date)
      ? '日付未選択'
      : model.loading
        ? '座席情報を取得中'
        : model.fetchError
          ? '更新失敗 · 再接続を待機中'
          : '自動更新中 · 5秒ごと';
  return (
    <div className="reservation-app">
      <header className="app-header">
        <h1>座席予約</h1>
        <div className="header-actions">
          {onAdmin && (
            <button
              className="button button-secondary"
              type="button"
              onClick={onAdmin}
            >
              予約管理へ
            </button>
          )}
          <button
            className="button button-danger"
            type="button"
            onClick={onLogout}
          >
            ログアウト
          </button>
        </div>
      </header>
      <main>
        <div className="filters">
          <ReservationFilters
            date={model.date}
            disabled={model.submitting}
            onDateChange={model.changeDate}
          />
          <div className="sync">
            <span>● {syncStatus}</span>
            <br />
            <span>
              最終確認 {model.lastChecked ? formatTime(model.lastChecked) : '—'}
              （日本時間）
            </span>
          </div>
        </div>
        <div className="layout">
          <SeatMap
            date={model.date}
            seats={model.seats}
            selectedNumber={model.selectedSeat?.seatNumber}
            canSelect={model.canSelect}
            past={model.past}
            loading={model.loading}
            fetchError={model.fetchError}
            onSelect={model.selectSeat}
          />
          <ReservationActionPanel
            date={model.date}
            seat={model.selectedSeat}
            enabled={model.canSelect}
            submitting={model.submitting}
            onReserve={model.reserve}
            onCancel={model.cancel}
          />
        </div>
        <ResultMessage
          notice={model.notice}
          fetchError={model.fetchError}
          loading={model.loading}
          onRetry={model.retry}
        />
      </main>
    </div>
  );
}
