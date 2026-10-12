import type { Seat } from '../types/reservation';

interface Props {
  seat: Seat;
  selected: boolean;
  disabled: boolean;
  onSelect: (seat: Seat) => void;
}

const labels = { AVAILABLE: '空席', MINE: '予約済み', OCCUPIED: '予約不可' };
const descriptions = {
  AVAILABLE: '空席',
  MINE: '予約済み（自分の予約）',
  OCCUPIED: '予約不可（他の人の予約）',
};

export function SeatButton({ seat, selected, disabled, onSelect }: Props) {
  return (
    <button
      type="button"
      className="seat"
      data-state={seat.status}
      aria-label={`座席${seat.seatNumber}、${descriptions[seat.status]}`}
      aria-pressed={selected}
      disabled={disabled || seat.status === 'OCCUPIED'}
      onClick={() => onSelect(seat)}
    >
      <strong>{String(seat.seatNumber).padStart(2, '0')}</strong>
      <span>{labels[seat.status]}</span>
      {selected && (
        <span className="selection-mark" aria-hidden="true">
          ✓
        </span>
      )}
    </button>
  );
}
