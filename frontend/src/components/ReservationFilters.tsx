interface Props {
  date: string;
  disabled: boolean;
  onDateChange: (value: string) => void;
}

export function ReservationFilters({ date, disabled, onDateChange }: Props) {
  return (
    <div className="filter-fields">
      <label className="date-field">
        利用日
        <input
          type="date"
          value={date}
          disabled={disabled}
          onChange={(e) => onDateChange(e.target.value)}
        />
      </label>
    </div>
  );
}
