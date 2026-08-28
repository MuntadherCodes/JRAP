import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';

export default function VerifyEmail() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const [state, setState] = useState<'working' | 'done' | 'error'>('working');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const token = params.get('token');
    if (!token) {
      setState('error');
      setError('Missing token');
      return;
    }
    api
      .verifyEmail(token)
      .then(() => setState('done'))
      .catch((e) => {
        setState('error');
        setError(e instanceof Error ? e.message : 'Verification failed');
      });
  }, [params]);

  return (
    <main className="auth-layout">
      <div className="card">
        <h1>{t('verifyEmailTitle')}</h1>
        {state === 'working' && <p>…</p>}
        {state === 'done' && (
          <>
            <p>{t('verifyEmailDone')}</p>
            <Link to="/login">{t('login')}</Link>
          </>
        )}
        {state === 'error' && <p className="error">{error}</p>}
      </div>
    </main>
  );
}
