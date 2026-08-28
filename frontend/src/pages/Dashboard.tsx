import { FormEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, session, UserDto } from '../api';

export default function Dashboard() {
  const { t } = useTranslation();
  const user = session().user;
  const [users, setUsers] = useState<UserDto[]>([]);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('ANALYST');
  const [error, setError] = useState<string | null>(null);
  const canManage = user?.role === 'OWNER' || user?.role === 'ANALYST';

  const load = () => {
    if (canManage) {
      api.listUsers().then(setUsers).catch(() => setUsers([]));
    }
  };

  useEffect(load, [canManage]);

  const invite = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      await api.invite({ email: inviteEmail, role: inviteRole });
      setInviteEmail('');
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed');
    }
  };

  return (
    <main className="content">
      <h1>{t('dashboard')}</h1>
      <p style={{ color: 'var(--muted)' }}>{t('journalsPlaceholder')}</p>
      {canManage && (
        <>
          <h2>{t('users')}</h2>
          <table>
            <thead>
              <tr>
                <th>{t('email')}</th>
                <th>{t('displayName')}</th>
                <th>{t('role')}</th>
                <th>{t('status')}</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.email}</td>
                  <td>{u.displayName}</td>
                  <td>{u.role}</td>
                  <td>{u.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {user?.role === 'OWNER' && (
            <form onSubmit={invite} style={{ marginTop: '1rem', gridTemplateColumns: '1fr auto auto', display: 'grid' }}>
              <input
                type="email"
                placeholder={t('email')}
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                required
              />
              <select value={inviteRole} onChange={(e) => setInviteRole(e.target.value)}>
                <option value="ANALYST">ANALYST</option>
                <option value="VIEWER">VIEWER</option>
                <option value="OWNER">OWNER</option>
              </select>
              <button type="submit">{t('invite')}</button>
            </form>
          )}
          {error && <p className="error">{error}</p>}
        </>
      )}
    </main>
  );
}
