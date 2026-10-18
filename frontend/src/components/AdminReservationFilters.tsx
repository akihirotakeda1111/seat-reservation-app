import { validDate } from '../utils/jaDate';
interface Props {
  date: string;
  loading: boolean;
  disabled: boolean;
  onDateChange: (date: string) => void;
  onFetch: () => void;
}
export function AdminReservationFilters({
  date,
  loading,
  disabled,
  onDateChange,
  onFetch,
}: Props) {
  return (
    <form
      className="filters admin-filters"
      onSubmit={(e) => {
        e.preventDefault();
        onFetch();
      }}
    >
      <label className="date-field">
        利用日
        <input
          type="date"
          value={date}
          disabled={disabled}
          onChange={(e) => onDateChange(e.target.value)}
        />
      </label>
      <button
        className="button button-primary"
        disabled={disabled || loading || !validDate(date)}
      >
        {loading ? '取得中…' : '予約一覧を取得'}
      </button>
      <span>過去日の予約はキャンセルできません。</span>
    </form>
  );
}
