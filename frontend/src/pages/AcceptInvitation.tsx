import { FormEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';

export default function AcceptInvitation() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      await api.acceptInvitation({ token: params.get('token') ?? '', password, displayName });
      setDone(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed');
    }
  };

  if (done) {
    return (
      <main className="auth-layout">
        <div className="card">
          <p>{t('verifyEmailDone')}</p>
          <Link to="/login">{t('login')}</Link>
        </div>
      </main>
    );
  }

  return (
    <main className="auth-layout">
      <div className="card">
        <h1>{t('acceptInvitationTitle')}</h1>
        <form onSubmit={submit}>
          <label>
            {t('displayName')}
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
          </label>
          <label>
            {t('password')}
            <input type="password" minLength={10} value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {error && <p className="error">{error}</p>}
          <button type="submit">{t('accept')}</button>
        </form>
      </div>
    </main>
  );
}
