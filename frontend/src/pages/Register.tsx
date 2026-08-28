import { FormEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api } from '../api';

export default function Register() {
  const { t } = useTranslation();
  const [organisationName, setOrganisationName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      await api.register({ organisationName, email, password, displayName });
      setDone(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Registration failed');
    }
  };

  if (done) {
    return (
      <main className="auth-layout">
        <div className="card">
          <p>{t('registerSuccess')}</p>
          <Link to="/login">{t('login')}</Link>
        </div>
      </main>
    );
  }

  return (
    <main className="auth-layout">
      <div className="card">
        <h1>{t('register')}</h1>
        <form onSubmit={submit}>
          <label>
            {t('organisationName')}
            <input value={organisationName} onChange={(e) => setOrganisationName(e.target.value)} required />
          </label>
          <label>
            {t('displayName')}
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
          </label>
          <label>
            {t('email')}
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <label>
            {t('password')}
            <input type="password" minLength={10} value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {error && <p className="error">{error}</p>}
          <button type="submit">{t('register')}</button>
        </form>
        <p>
          {t('haveAccount')} <Link to="/login">{t('login')}</Link>
        </p>
      </div>
    </main>
  );
}
