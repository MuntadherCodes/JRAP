import { FormEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api, JournalDto, session } from '../api';

export default function Journals() {
  const { t } = useTranslation();
  const user = session().user;
  const [journals, setJournals] = useState<JournalDto[]>([]);
  const [mode, setMode] = useState<'issn' | 'url'>('issn');
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canRegister = user?.role === 'OWNER' || user?.role === 'ANALYST';

  const load = () => {
    api.listJournals().then(setJournals).catch(() => setJournals([]));
  };

  useEffect(load, []);

  const register = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api.registerJournal(mode === 'issn' ? { issn: value } : { url: value });
      setValue('');
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Registration failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="content">
      <h1>{t('journals')}</h1>
      {canRegister && (
        <form onSubmit={register} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto', gap: '0.6rem' }}>
          <select value={mode} onChange={(e) => setMode(e.target.value as 'issn' | 'url')}>
            <option value="issn">ISSN</option>
            <option value="url">URL</option>
          </select>
          <input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={mode === 'issn' ? '2708-9134' : 'https://journal.example.org'}
            required
          />
          <button type="submit" disabled={busy}>
            {busy ? '…' : t('registerJournal')}
          </button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
      <table style={{ marginTop: '1rem' }}>
        <thead>
          <tr>
            <th>{t('journalTitle')}</th>
            <th>ISSN-L</th>
            <th>{t('publisher')}</th>
            <th>{t('platform')}</th>
            <th>{t('status')}</th>
          </tr>
        </thead>
        <tbody>
          {journals.map((j) => (
            <tr key={j.id}>
              <td>
                <Link to={`/journals/${j.id}`}>{j.title ?? j.issnL ?? j.id}</Link>
              </td>
              <td>{j.issnL}</td>
              <td>{j.publisher}</td>
              <td>{j.platform}</td>
              <td>{j.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}
