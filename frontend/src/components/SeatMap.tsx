import type { Seat } from '../types/reservation';
import { formatDate, validDate } from '../utils/jaDate';
import { SeatButton } from './SeatButton';

interface Props {
  date: string;
  seats: Seat[];
  selectedNumber?: number;
  canSelect: boolean;
  past: boolean;
  loading: boolean;
  fetchError: boolean;
  onSelect: (seat: Seat) => void;
}

export function SeatMap({
  date,
  seats,
  selectedNumber,
  canSelect,
  past,
  loading,
  fetchError,
  onSelect,
}: Props) {
  return (
    <section
      className={`floor${past ? ' past' : ''}`}
      aria-label="座席一覧"
      aria-busy={loading}
    >
      <div className="floor-title">
        <h2>{formatDate(date)}</h2>
        <span className="muted">
          {seats.length
            ? `空席 ${seats.filter((s) => s.status === 'AVAILABLE').length} / ${seats.length}`
            : '—'}
        </span>
      </div>
      <div className="legend">
        <span>
          <i className="swatch" aria-hidden="true" />
          空席
        </span>
        <span>
          <i className="swatch mine" aria-hidden="true" />
          予約済み
        </span>
        <span>
          <i className="swatch other" aria-hidden="true" />
          予約不可
        </span>
        <span>✓ 選択中</span>
      </div>
      {seats.length > 0 ? (
        <div className="seat-grid">
          {seats.map((seat) => (
            <SeatButton
              key={seat.seatNumber}
              seat={seat}
              selected={seat.seatNumber === selectedNumber}
              disabled={!canSelect}
              onSelect={onSelect}
            />
          ))}
        </div>
      ) : (
        <p className="empty-state">
          {!validDate(date)
            ? '利用日を指定してください。'
            : loading
              ? '座席情報を取得しています…'
              : fetchError
                ? '座席情報を取得できませんでした。'
                : '登録されている座席はありません。'}
        </p>
      )}
      <p className="footnote">
        {past
          ? '過去の日付は、予約・キャンセルできません。'
          : '複数席の同時選択はできません。'}
      </p>
    </section>
  );
}
