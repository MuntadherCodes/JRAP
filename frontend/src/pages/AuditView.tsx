import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, AuditDto, SkippedUrlDto, SnapshotDto } from '../api';

const ACTIVE = ['PENDING', 'RUNNING'];

export default function AuditView() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [audit, setAudit] = useState<AuditDto | null>(null);
  const [snapshots, setSnapshots] = useState<SnapshotDto[]>([]);
  const [skipped, setSkipped] = useState<SkippedUrlDto[]>([]);

  useEffect(() => {
    if (!id) return;
    let timer: ReturnType<typeof setTimeout>;
    const refresh = async () => {
      try {
        const current = await api.audit(id);
        setAudit(current);
        if (!ACTIVE.includes(current.status)) {
          setSnapshots(await api.auditSnapshots(id));
          setSkipped(await api.auditSkipped(id));
          return; // terminal — stop polling
        }
      } catch {
        return;
      }
      timer = setTimeout(refresh, 5000); // NFR-PERF-1: live status while running
    };
    refresh();
    return () => clearTimeout(timer);
  }, [id]);

  if (!audit) {
    return (
      <main className="content">
        <p>…</p>
      </main>
    );
  }

  const byType = snapshots.reduce<Record<string, number>>((acc, s) => {
    acc[s.pageType] = (acc[s.pageType] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <main className="content">
      <p>
        <Link to={`/journals/${audit.journalId}`}>← {t('journals')}</Link>
      </p>
      <h1>
        {t('audit')} — {audit.status}
      </h1>
      <p style={{ color: 'var(--muted)' }}>
        {t('stage')}: {audit.stage} · {t('pagesFetched')}: {audit.pagesFetched}/{audit.pageCap} ·{' '}
        {t('pagesSkipped')}: {audit.pagesSkipped}
      </p>
      {audit.error && <p className="error">{audit.error}</p>}
      {ACTIVE.includes(audit.status) && (
        <button className="secondary" onClick={() => api.cancelAudit(audit.id)}>
          {t('cancel')}
        </button>
      )}
      {!ACTIVE.includes(audit.status) && (
        <>
          <h2>{t('pageInventory')}</h2>
          <table>
            <thead>
              <tr>
                <th>{t('pageType')}</th>
                <th>{t('count')}</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(byType)
                .sort((a, b) => b[1] - a[1])
                .map(([type, count]) => (
                  <tr key={type}>
                    <td>{type}</td>
                    <td>{count}</td>
                  </tr>
                ))}
            </tbody>
          </table>
          {skipped.length > 0 && (
            <>
              <h2>
                {t('skippedUrls')} ({skipped.length})
              </h2>
              <table>
                <thead>
                  <tr>
                    <th>URL</th>
                    <th>{t('status')}</th>
                    <th>{t('reason')}</th>
                  </tr>
                </thead>
                <tbody>
                  {skipped.map((s) => (
                    <tr key={s.url}>
                      <td style={{ wordBreak: 'break-all' }}>{s.url}</td>
                      <td>{s.status}</td>
                      <td>{s.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </>
      )}
    </main>
  );
}
