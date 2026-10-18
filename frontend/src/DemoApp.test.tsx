import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { DemoApp } from './DemoApp';

vi.mock('./pages/SeatReservationPage', () => ({
  SeatReservationPage: ({
    accessToken,
    onAdmin,
    onLogout,
    onUnauthorized,
  }: {
    accessToken: string;
    onAdmin?: () => void;
    onLogout: () => void;
    onUnauthorized: () => void;
  }) => (
    <>
      <p>一般画面 {accessToken}</p>
      {onAdmin && <button onClick={onAdmin}>管理へ</button>}
      <button onClick={onLogout}>終了</button>
      <button onClick={onUnauthorized}>401</button>
    </>
  ),
}));
vi.mock('./pages/AdminReservationPage', () => ({
  AdminReservationPage: ({
    onReservations,
  }: {
    onReservations: () => void;
  }) => (
    <>
      <p>管理画面</p>
      <button onClick={onReservations}>戻る</button>
    </>
  ),
}));
it.each(['USER', 'ADMIN'] as const)(
  '固定ユーザーの表示と%sの管理導線',
  (role) => {
    render(<DemoApp userId="demo-owner" role={role} />);
    expect(screen.getByRole('note')).toHaveTextContent(`demo-owner（${role}）`);
    expect(screen.getByText('一般画面 local-demo')).toBeInTheDocument();
    expect(location.pathname).toBe('/reservations');
    if (role === 'ADMIN') {
      fireEvent.click(screen.getByText('管理へ'));
      expect(screen.getByText('管理画面')).toBeInTheDocument();
      expect(location.pathname).toBe('/admin/reservations');
      fireEvent.click(screen.getByText('戻る'));
    } else expect(screen.queryByText('管理へ')).toBeNull();
    fireEvent.click(screen.getByText('終了'));
    expect(screen.queryByText('一般画面 local-demo')).toBeNull();
    fireEvent.click(screen.getByText('操作を再開'));
    expect(screen.getByText('一般画面 local-demo')).toBeInTheDocument();
  },
);
it('401で画面を破棄し設定確認を案内する', () => {
  render(<DemoApp userId="demo-owner" role="USER" />);
  fireEvent.click(screen.getByText('401'));
  expect(screen.getByRole('alert')).toHaveTextContent('固定ユーザー設定');
  expect(screen.queryByText('一般画面 local-demo')).toBeNull();
});
