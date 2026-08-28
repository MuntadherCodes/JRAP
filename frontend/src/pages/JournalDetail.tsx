import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, FindingDto, JournalDetailDto } from '../api';

const severityColor: Record<string, string> = {
  CRITICAL: '#b3261e',
  HIGH: '#c4441c',
  MEDIUM: '#a06a00',
  LOW: '#4a5b8f',
  INFO: '#6a6f85',
};

export default function JournalDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [detail, setDetail] = useState<JournalDetailDto | null>(null);
  const [findings, setFindings] = useState<FindingDto[]>([]);

  useEffect(() => {
    if (!id) return;
    api.journalDetail(id).then(setDetail).catch(() => setDetail(null));
    api.journalFindings(id).then(setFindings).catch(() => setFindings([]));
  }, [id]);

  if (!detail) {
    return (
      <main className="content">
        <p>…</p>
      </main>
    );
  }

  const j = detail.journal;
  return (
    <main className="content">
      <p>
        <Link to="/journals">← {t('journals')}</Link>
      </p>
      <h1>{j.title ?? j.issnL}</h1>
      <p style={{ color: 'var(--muted)' }}>
        {j.publisher} {j.country ? `· ${j.country}` : ''} {j.platform ? `· ${j.platform}` : ''}
      </p>
      <h2>{t('identityBySource')}</h2>
      <table>
        <thead>
          <tr>
            <th>{t('source')}</th>
            <th>{t('availability')}</th>
            <th>{t('journalTitle')}</th>
            <th>{t('publisher')}</th>
            <th>ISSN (p/e)</th>
            <th>ISSN-L</th>
          </tr>
        </thead>
        <tbody>
          {detail.identity.map((r) => (
            <tr key={r.source}>
              <td>{r.source}</td>
              <td>{r.availability}</td>
              <td>{r.title}</td>
              <td>{r.publisher}</td>
              <td>
                {r.issnPrint ?? '—'} / {r.issnOnline ?? '—'}
              </td>
              <td>{r.issnL}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>
        {t('findings')} ({findings.length})
      </h2>
      {findings.map((f) => (
        <div key={f.id} className="card" style={{ maxWidth: 'none', marginBottom: '0.8rem', padding: '1rem' }}>
          <strong style={{ color: severityColor[f.severity] ?? 'inherit' }}>
            [{f.severity}] {f.title}
          </strong>
          <p style={{ margin: '0.4rem 0 0' }}>{f.description}</p>
          <p style={{ margin: '0.3rem 0 0', color: 'var(--muted)', fontSize: '0.85rem' }}>
            {f.code} · {f.detectorVersion} · {t('evidenceCount', { count: f.evidenceItemIds.length })}
          </p>
        </div>
      ))}
    </main>
  );
}
