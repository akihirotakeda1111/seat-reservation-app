import type { Seat } from '../types/reservation';
import { formatDate } from '../utils/jaDate';

interface Props {
  date: string;
  seat: Seat | null;
  enabled: boolean;
  submitting: boolean;
  onReserve: () => void;
  onCancel: () => void;
}

export function ReservationActionPanel({
  date,
  seat,
  enabled,
  submitting,
  onReserve,
  onCancel,
}: Props) {
  return (
    <aside
      className="action-panel"
      aria-label="予約操作"
      aria-busy={submitting}
    >
      <h2>選択した座席</h2>
      <div className="selection" aria-live="polite">
        <div className="muted">{formatDate(date)}</div>
        <div className="seat-name">
          {seat ? `座席 ${String(seat.seatNumber).padStart(2, '0')}` : '未選択'}
        </div>
        <div className="muted">
          {seat?.status === 'AVAILABLE'
            ? '空席 · 予約できます'
            : seat?.status === 'MINE'
              ? '予約済み· キャンセルできます'
              : '座席を選んでください'}
        </div>
      </div>
      <div className="actions">
        <button
          type="button"
          className="action primary"
          disabled={!enabled || seat?.status !== 'AVAILABLE'}
          onClick={onReserve}
        >
          予約する
        </button>
        <button
          type="button"
          className="action"
          disabled={!enabled || seat?.status !== 'MINE'}
          onClick={onCancel}
        >
          予約をキャンセル
        </button>
      </div>
      {submitting && <p role="status">処理しています…</p>}
      <p className="footnote">キャンセルできるのは、自分の予約だけです。</p>
    </aside>
  );
}
