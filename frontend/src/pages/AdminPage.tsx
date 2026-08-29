import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, AdminOrgRowDto } from '../api';

/** FR-ADM-1/2: platform-operator console (visible only to configured admin emails). */
export default function AdminPage() {
  const { t } = useTranslation();
  const [orgs, setOrgs] = useState<AdminOrgRowDto[]>([]);
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<Record<string, unknown> | null>(null);
  const [blocklist, setBlocklist] = useState('');
  const [rubric, setRubric] = useState('');
  const [error, setError] = useState('');
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(async () => {
    try {
      setOrgs(await api.adminOrganisations());
      const current = await api.adminSettings();
      setSettings(current);
      try {
        setBlocklist(JSON.parse(current['crawl.blocklist'] ?? '[]').join('\n'));
      } catch { setBlocklist(''); }
      try {
        setRubric(JSON.parse(current['analysis.rubric-version'] ?? '""'));
      } catch { setRubric(''); }
      setStatus(await api.adminStatus());
      setForbidden(false);
    } catch (e) {
      if (e instanceof Error && e.message.includes('platform administrators')) {
        setForbidden(true);
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (forbidden) {
    return <main className="content"><h1>{t('adminConsole')}</h1><p>{t('notAdmin')}</p></main>;
  }

  const act = async (fn: () => Promise<unknown>) => {
    setError('');
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <main className="content" style={{ maxWidth: 1000 }}>
      <h1>{t('adminConsole')}</h1>
      {error && <p className="error">{error}</p>}

      <h2>{t('organisations')}</h2>
      <table>
        <thead>
          <tr>
            <th>{t('organisationName')}</th><th>{t('status')}</th>
            <th>{t('journals')}</th><th>{t('quota')}</th><th></th>
          </tr>
        </thead>
        <tbody>
          {orgs.map(org => (
            <tr key={org.id}>
              <td>{org.name}</td>
              <td>{org.status}</td>
              <td>{org.journals}</td>
              <td>
                <input type="number" defaultValue={org.maxJournals} style={{ width: 70 }}
                       onBlur={e => {
                         const value = parseInt(e.target.value, 10);
                         if (!Number.isNaN(value) && value !== org.maxJournals) {
                           act(() => api.adminSetQuota(org.id, value));
                         }
                       }} />
              </td>
              <td>
                {org.status === 'ACTIVE' ? (
                  <button className="secondary"
                          onClick={() => act(() => api.adminSetOrgStatus(org.id, 'ARCHIVED'))}>
                    {t('suspend')}
                  </button>
                ) : org.status === 'ARCHIVED' ? (
                  <button className="secondary"
                          onClick={() => act(() => api.adminSetOrgStatus(org.id, 'ACTIVE'))}>
                    {t('reactivate')}
                  </button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>{t('platformSettings')}</h2>
      <div className="card">
        <label style={{ display: 'block', marginBottom: 8 }}>
          <span className="secondary">{t('crawlBlocklist')}</span>
          <textarea rows={3} style={{ width: '100%' }} value={blocklist}
                    onChange={e => setBlocklist(e.target.value)} />
        </label>
        <label style={{ display: 'block', marginBottom: 8 }}>
          <span className="secondary">{t('rubricVersion')}</span>
          <input value={rubric} onChange={e => setRubric(e.target.value)} placeholder="1.0" />
        </label>
        <button onClick={() => act(async () => {
          await api.adminPutSetting('crawl.blocklist', JSON.stringify(
            blocklist.split('\n').map(h => h.trim()).filter(Boolean)));
          if (rubric.trim()) {
            await api.adminPutSetting('analysis.rubric-version', JSON.stringify(rubric.trim()));
          }
        })}>{t('save')}</button>
      </div>

      <h2>{t('sourceHealth')}</h2>
      {status && (
        <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12 }} className="card">
          {JSON.stringify(status, null, 2)}
        </pre>
      )}
      <p className="secondary" style={{ fontSize: 12 }}>
        {Object.keys(settings).length} setting(s) stored.
      </p>
    </main>
  );
}
