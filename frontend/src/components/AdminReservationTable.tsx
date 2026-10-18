import type { AdminReservation } from '../types/adminReservation';
import { formatDate, formatTime } from '../utils/jaDate';
interface Props {
  date: string;
  reservations: AdminReservation[];
  loaded: boolean;
  loading: boolean;
  disabled: boolean;
  past: boolean;
  lastChecked: Date | null;
  onCancel: (row: AdminReservation) => void;
}
export function AdminReservationTable({
  date,
  reservations,
  loaded,
  loading,
  disabled,
  past,
  lastChecked,
  onCancel,
}: Props) {
  return (
    <section className="admin-list" aria-label="予約一覧" aria-busy={loading}>
      <div className="admin-list-header">
        <h2>
          {formatDate(date)}の予約{' '}
          {loaded && <small>{reservations.length}件</small>}
        </h2>
        <span>
          最終取得 {lastChecked ? formatTime(lastChecked) : '—'}（日本時間）
        </span>
      </div>
      {loading && <p role="status">予約一覧を取得しています…</p>}
      {!loading && !loaded && (
        <p>利用日を選択し、予約一覧を取得してください。</p>
      )}
      {loaded && reservations.length === 0 && <p>指定日の予約はありません。</p>}
      {loaded && reservations.length > 0 && (
        <div className="admin-table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">座席番号</th>
                <th scope="col">予約者ID</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {reservations.map((row) => (
                <tr key={row.id}>
                  <td>{String(row.seatNumber).padStart(2, '0')}</td>
                  <td className="admin-user-id">{row.userId}</td>
                  <td>
                    <button
                      className="button button-danger"
                      disabled={disabled || loading || past}
                      aria-label={`座席${row.seatNumber}の予約をキャンセル`}
                      onClick={() => onCancel(row)}
                    >
                      キャンセル
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
