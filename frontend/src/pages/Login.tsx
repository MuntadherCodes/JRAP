import { FormEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { api, ApiError, storeSession } from '../api';

export default function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [totpRequired, setTotpRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      const tokens = await api.login({ email, password, totpCode: totpCode || undefined });
      storeSession(tokens);
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError && e.problem.title === 'totp-required') {
        setTotpRequired(true);
      } else {
        setError(e instanceof Error ? e.message : 'Login failed');
      }
    }
  };

  return (
    <main className="auth-layout">
      <div className="card">
        <h1>{t('login')}</h1>
        <form onSubmit={submit}>
          <label>
            {t('email')}
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <label>
            {t('password')}
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {totpRequired && (
            <label>
              {t('totpCode')}
              <input value={totpCode} onChange={(e) => setTotpCode(e.target.value)} required />
            </label>
          )}
          {error && <p className="error">{error}</p>}
          <button type="submit">{t('login')}</button>
        </form>
        <p>
          {t('needAccount')} <Link to="/register">{t('register')}</Link>
        </p>
      </div>
    </main>
  );
}
