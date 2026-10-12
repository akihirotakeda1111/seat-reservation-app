import type { DemoUser } from '../types/reservation';

interface Props {
  date: string;
  user: DemoUser;
  disabled: boolean;
  onDateChange: (value: string) => void;
  onUserChange: (value: DemoUser) => void;
}

export function ReservationFilters({
  date,
  user,
  disabled,
  onDateChange,
  onUserChange,
}: Props) {
  return (
    <div className="filter-fields">
      <label>
        利用日
        <input
          type="date"
          value={date}
          disabled={disabled}
          onChange={(e) => onDateChange(e.target.value)}
        />
      </label>
      <label>
        仮ユーザー
        <select
          value={user}
          disabled={disabled}
          onChange={(e) => onUserChange(e.target.value as DemoUser)}
        >
          <option value="demo-user-1">ユーザー1</option>
          <option value="demo-user-2">ユーザー2</option>
        </select>
      </label>
    </div>
  );
}
