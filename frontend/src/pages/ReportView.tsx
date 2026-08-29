import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, ReportDto } from '../api';

const verdictColor: Record<string, string> = {
  READY: '#1b7f4d',
  CONDITIONAL: '#a06a00',
  NOT_READY: '#b3261e',
};

const phaseLabels: Record<string, string> = {
  P0_3: '0–3',
  P3_6: '3–6',
  P6_12: '6–12',
};

/**
 * The report reader (FR-RPT-1/3/4): structured sections with citation chips,
 * guard-failing sentences highlighted and editable, release flow, exports, roadmap.
 */
export default function ReportView() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [report, setReport] = useState<ReportDto | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!id) return;
    try {
      setReport(await api.getReport(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  if (!report) {
    return (
      <main className="content">
        <p>{error || '…'}</p>
      </main>
    );
  }

  const act = async (fn: () => Promise<unknown>) => {
    setError('');
    try {
      await fn();
      setEditing(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const draft = report.status === 'DRAFT';

  return (
    <main className="content" style={{ maxWidth: 900 }}>
      <p>
        <Link to={`/audits/${report.auditId}`}>← {t('audit')}</Link>
      </p>
      <h1>{t('reportTitle')} v{report.version}</h1>
      <div className="card" style={{ borderInlineStart: `4px solid ${verdictColor[report.verdict] ?? '#555'}` }}>
        <strong style={{ color: verdictColor[report.verdict] }}>
          {t('verdict')}: {report.verdict.replace('_', ' ')}
        </strong>
        {' · '}{report.status === 'RELEASED'
          ? `${t('released')} · SHA-256 ${report.contentHash?.slice(0, 16)}…`
          : report.guardPassed ? t('draftGuardPassed') : t('draftGuardFailed')}
        <div style={{ marginTop: 8, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {draft && report.guardPassed && (
            <button onClick={() => act(() => api.releaseReport(report.id))}>{t('release')}</button>
          )}
          <button className="secondary" onClick={() => api.exportReport(report.id, 'html')}>HTML</button>
          <button className="secondary" onClick={() => api.exportReport(report.id, 'docx')}>DOCX</button>
          <button className="secondary" onClick={() => api.exportReport(report.id, 'pdf')}>PDF</button>
          <button className="secondary" onClick={() => act(() => api.adoptRoadmap(report.id))}>
            {t('adoptRoadmap')}
          </button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}

      {report.sections.map(section => (
        <section key={section.id}>
          <h2>{section.title}</h2>
          {section.sentences.map(sentence => (
            <div
              key={sentence.id}
              style={{
                marginBottom: 6,
                padding: sentence.guard === 'FAIL' ? '6px 8px' : undefined,
                background: sentence.guard === 'FAIL' ? '#fdecea' : undefined,
                border: sentence.guard === 'FAIL' ? '1px solid #b3261e' : undefined,
              }}
            >
              <span dir="auto">{sentence.text}</span>
              {sentence.evidenceItemIds.length > 0 && (
                <sup title={t('citations', { count: sentence.evidenceItemIds.length })}
                     style={{ color: '#4650dd', marginInlineStart: 2 }}>
                  [{sentence.evidenceItemIds.length}]
                </sup>
              )}
              {sentence.guard === 'FAIL' && draft && (
                <div style={{ marginTop: 6 }}>
                  <em className="error">{t('guardFailedSentence')}</em>{' '}
                  {editing === sentence.id ? (
                    <span style={{ display: 'flex', gap: 6 }}>
                      <input style={{ flex: 1 }} value={editText}
                             onChange={e => setEditText(e.target.value)} />
                      <button onClick={() => act(() =>
                        api.editReportSentence(report.id, sentence.id, editText))}>
                        {t('save')}
                      </button>
                    </span>
                  ) : (
                    <>
                      <button className="secondary" onClick={() => {
                        setEditing(sentence.id);
                        setEditText(sentence.text);
                      }}>{t('edit')}</button>{' '}
                      <button className="secondary" onClick={() => act(() =>
                        api.editReportSentence(report.id, sentence.id, undefined, true))}>
                        {t('remove')}
                      </button>
                    </>
                  )}
                </div>
              )}
            </div>
          ))}
        </section>
      ))}

      <h2>{t('roadmap')}</h2>
      <table>
        <thead>
          <tr>
            <th>{t('months')}</th>
            <th>{t('action')}</th>
            <th>{t('tag')}</th>
            <th>{t('completionCriterion')}</th>
          </tr>
        </thead>
        <tbody>
          {report.roadmap.map(action => (
            <tr key={action.id}>
              <td>{phaseLabels[action.phase]}</td>
              <td><strong>{action.title}</strong><br /><span className="secondary">{action.description}</span></td>
              <td style={{ color: action.tag === 'MUST_FIX' ? '#b3261e' : '#1b7f4d' }}>
                {action.tag === 'MUST_FIX' ? t('mustFix') : t('strengthens')}
              </td>
              <td>{action.completionCriterion}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {report.exclusions.length > 0 && (
        <>
          <h2>{t('exclusionsAnnex')}</h2>
          <table>
            <thead>
              <tr><th>Code</th><th>{t('findings')}</th><th>{t('reason')}</th></tr>
            </thead>
            <tbody>
              {report.exclusions.map(exclusion => (
                <tr key={exclusion.findingId}>
                  <td><code>{exclusion.code}</code></td>
                  <td>{exclusion.title}</td>
                  <td>{exclusion.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </main>
  );
}
