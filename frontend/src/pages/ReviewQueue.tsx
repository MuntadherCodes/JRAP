import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, GateDto, QueueItemDto, SnapshotTextDto } from '../api';
import { SeverityBadge, StatusBadge } from '../components/ui';

type PendingAction = 'reject' | 'exclude' | 'annotate' | 'severity' | null;

/**
 * The analyst's core screen (SRS §3.2.1, FR-REV-1/2, AC-6): a keyboard-driven queue of
 * findings and low-confidence extractions. j/k or arrows navigate; c confirms; r rejects
 * (reason required); x excludes; a annotates. Extraction rows show the source snapshot
 * side-by-side for correction.
 */
export default function ReviewQueue() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [items, setItems] = useState<QueueItemDto[]>([]);
  const [gate, setGate] = useState<GateDto | null>(null);
  const [filter, setFilter] = useState('all');
  const [selected, setSelected] = useState(0);
  const [pending, setPending] = useState<PendingAction>(null);
  const [inputValue, setInputValue] = useState('');
  const [severityValue, setSeverityValue] = useState('MEDIUM');
  const [snapshot, setSnapshot] = useState<SnapshotTextDto | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const reload = useCallback(async () => {
    if (!id) return;
    try {
      const page = await api.reviewQueue(id, filter);
      setItems(page.items);
      setGate(await api.reviewGate(id));
      setSelected(s => Math.min(s, Math.max(0, page.items.length - 1)));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [id, filter]);

  useEffect(() => {
    reload();
  }, [reload]);

  const current = items[selected] ?? null;

  // Side-by-side snapshot + editable fields for the selected extraction row (FR-REV-2).
  useEffect(() => {
    setPending(null);
    setInputValue('');
    setError('');
    if (current && current.kind !== 'FINDING') {
      const initial: Record<string, string> = { name: current.title };
      Object.entries(current.fields ?? {}).forEach(([k, v]) => {
        initial[k] = v ?? '';
      });
      setFields(initial);
      if (current.snapshotId) {
        api.snapshotText(current.snapshotId).then(setSnapshot).catch(() => setSnapshot(null));
      } else {
        setSnapshot(null);
      }
    } else {
      setSnapshot(null);
      setFields({});
    }
  }, [current?.id]);

  const act = async (fn: () => Promise<void>) => {
    setError('');
    try {
      await fn();
      setPending(null);
      setInputValue('');
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const confirmCurrent = () => {
    if (!current) return;
    if (current.kind === 'FINDING') act(() => api.confirmFinding(current.id, id!));
    else if (current.kind === 'BOARD_MEMBER') act(() => api.confirmBoardMember(current.id));
    else act(() => api.confirmArticle(current.id));
  };

  const submitPending = () => {
    if (!current) return;
    if (pending === 'reject') act(() => api.rejectFinding(current.id, id!, inputValue));
    else if (pending === 'exclude') act(() => api.excludeFinding(current.id, id!, inputValue));
    else if (pending === 'annotate') act(() => api.annotateFinding(current.id, id!, inputValue));
    else if (pending === 'severity') act(() => api.editFindingSeverity(current.id, id!, severityValue, inputValue));
  };

  const saveCorrection = () => {
    if (!current) return;
    if (current.kind === 'BOARD_MEMBER') {
      act(() =>
        api.correctBoardMember(current.id, {
          name: fields.name,
          role: fields.role,
          institution: fields.institution,
          country: fields.country,
        }),
      );
    } else if (current.kind === 'ARTICLE') {
      act(() =>
        api.correctArticle(current.id, {
          title: fields.name,
          doi: fields.doi,
          dateSubmitted: fields.dateSubmitted,
          dateAccepted: fields.dateAccepted,
          datePublished: fields.datePublished,
          abstractLanguage: fields.abstractLanguage,
        }),
      );
    }
  };

  // AC-6: keyboard-driven review at >= 200 findings/hour.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') {
        if (e.key === 'Escape') setPending(null);
        if (e.key === 'Enter' && pending) {
          e.preventDefault();
          submitPending();
        }
        return;
      }
      if (e.key === 'j' || e.key === 'ArrowDown') {
        e.preventDefault();
        setSelected(s => Math.min(items.length - 1, s + 1));
      } else if (e.key === 'k' || e.key === 'ArrowUp') {
        e.preventDefault();
        setSelected(s => Math.max(0, s - 1));
      } else if (e.key === 'c') {
        confirmCurrent();
      } else if (e.key === 'r' && current?.kind === 'FINDING') {
        setPending('reject');
        setTimeout(() => inputRef.current?.focus(), 0);
      } else if (e.key === 'x' && current?.kind === 'FINDING') {
        setPending('exclude');
        setTimeout(() => inputRef.current?.focus(), 0);
      } else if (e.key === 'a' && current?.kind === 'FINDING') {
        setPending('annotate');
        setTimeout(() => inputRef.current?.focus(), 0);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [items, current, pending, inputValue, severityValue]);

  useEffect(() => {
    listRef.current
      ?.querySelectorAll('[data-row]')
      [selected]?.scrollIntoView({ block: 'nearest' });
  }, [selected]);

  if (!id) return null;

  const decided = items.filter(
    i => i.kind === 'FINDING' && (i.status === 'CONFIRMED' || i.status === 'REJECTED' || i.excluded),
  ).length;
  const findingCount = items.filter(i => i.kind === 'FINDING').length;

  return (
    <main className="content" style={{ maxWidth: 1200 }}>
      <p>
        <Link to={`/audits/${id}`}>← {t('audit')}</Link>
      </p>
      <h1>{t('reviewQueue')}</h1>
      {gate && (
        <div className="card" style={{ borderInlineStart: `4px solid ${gate.releasable ? '#1b7f4d' : '#a06a00'}` }}>
          <strong>{t('releaseGate')}:</strong>{' '}
          {gate.releasable ? t('releasable') : t('notReleasable', { count: gate.needsVerification })}
          {gate.excluded > 0 && <> · {t('excludedCount', { count: gate.excluded })}</>}
          {findingCount > 0 && <> · {t('reviewedProgress', { done: decided, total: findingCount })}</>}
        </div>
      )}
      <p className="hint">
        <kbd>↑</kbd>/<kbd>↓</kbd> {t('filterAll').toLowerCase()} · <kbd>c</kbd> {t('confirm').toLowerCase()} ·{' '}
        <kbd>r</kbd> {t('reject').toLowerCase()} · <kbd>x</kbd> {t('exclude').toLowerCase()} ·{' '}
        <kbd>a</kbd> {t('annotate').toLowerCase()}
      </p>
      <div className="segmented" style={{ marginBottom: 12 }}>
        {[
          ['all', t('filterAll')],
          ['open', t('filterFindings')],
          ['needs-verification', t('filterNeedsVerification')],
          ['extractions', t('filterExtractions')],
        ].map(([key, label]) => (
          <button
            key={key}
            className={filter === key ? 'active' : ''}
            onClick={() => {
              setFilter(key);
              setSelected(0);
            }}
          >
            {label}
          </button>
        ))}
      </div>
      {error && <p className="error">{error}</p>}
      {items.length === 0 ? (
        <p>{t('queueEmpty')}</p>
      ) : (
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          <div ref={listRef} style={{ flex: '0 0 42%', maxHeight: '70vh', overflowY: 'auto' }}>
            {items.map((item, index) => (
              <div
                key={item.id}
                data-row
                className={`card queue-item ${index === selected ? 'selected' : ''} ${
                  item.status === 'CONFIRMED' || item.status === 'REJECTED' ? 'decided' : ''
                }`}
                onClick={() => setSelected(index)}
                style={{ padding: '10px 14px', marginBottom: 8 }}
              >
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 2 }}>
                  {item.severity && <SeverityBadge severity={item.severity} />}
                  {item.code && <code>{item.code}</code>}
                  <StatusBadge status={item.excluded ? 'EXCLUDED' : item.status} />
                  {item.kind !== 'FINDING' && (
                    <span className="hint">
                      {item.kind.replace('_', ' ')} {item.confidence != null && `· ${Math.round(item.confidence * 100)}%`}
                    </span>
                  )}
                </div>
                <div style={{ fontSize: '0.92rem' }}>{item.title}</div>
              </div>
            ))}
          </div>
          {current && (
            <div style={{ flex: 1 }}>
              <div className="card">
                <h3 style={{ marginTop: 0 }}>{current.title}</h3>
                <p>{current.description}</p>
                {current.reviewNote && (
                  <p className="secondary">
                    {t('note')}: {current.reviewNote}
                  </p>
                )}
                {current.kind === 'FINDING' ? (
                  <>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <button onClick={confirmCurrent}>{t('confirm')} (c)</button>
                      <button className="secondary" onClick={() => { setPending('reject'); setTimeout(() => inputRef.current?.focus(), 0); }}>
                        {t('reject')} (r)
                      </button>
                      {current.status === 'NEEDS_VERIFICATION' && !current.excluded && (
                        <button className="secondary" onClick={() => { setPending('exclude'); setTimeout(() => inputRef.current?.focus(), 0); }}>
                          {t('exclude')} (x)
                        </button>
                      )}
                      {current.excluded && (
                        <button className="secondary" onClick={() => act(() => api.includeFinding(current.id, id!))}>
                          {t('include')}
                        </button>
                      )}
                      <button className="secondary" onClick={() => { setPending('annotate'); setTimeout(() => inputRef.current?.focus(), 0); }}>
                        {t('annotate')} (a)
                      </button>
                      <button className="secondary" onClick={() => { setPending('severity'); setTimeout(() => inputRef.current?.focus(), 0); }}>
                        {t('changeSeverity')}
                      </button>
                    </div>
                    {pending && (
                      <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        {pending === 'severity' && (
                          <select value={severityValue} onChange={e => setSeverityValue(e.target.value)}>
                            {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map(s => (
                              <option key={s}>{s}</option>
                            ))}
                          </select>
                        )}
                        <input
                          ref={inputRef}
                          style={{ flex: 1, minWidth: 200 }}
                          placeholder={pending === 'annotate' ? t('note') : t('reasonRequired')}
                          value={inputValue}
                          onChange={e => setInputValue(e.target.value)}
                        />
                        <button onClick={submitPending}>{t('save')}</button>
                      </div>
                    )}
                  </>
                ) : (
                  <>
                    {Object.entries(fields).map(([key, value]) => (
                      <label key={key} style={{ display: 'block', marginBottom: 6 }}>
                        <span className="secondary" style={{ fontSize: 12 }}>{key}</span>
                        <input
                          style={{ width: '100%' }}
                          value={value ?? ''}
                          onChange={e => setFields(f => ({ ...f, [key]: e.target.value }))}
                        />
                      </label>
                    ))}
                    <div style={{ display: 'flex', gap: 8 }}>
                      <button onClick={saveCorrection}>{t('correctAndApprove')}</button>
                      <button className="secondary" onClick={confirmCurrent}>
                        {t('approveAsIs')} (c)
                      </button>
                    </div>
                  </>
                )}
              </div>
              {current.kind !== 'FINDING' && (
                <div className="card" style={{ maxHeight: '40vh', overflowY: 'auto' }}>
                  <h4 style={{ marginTop: 0 }}>{t('snapshotView')}</h4>
                  {current.excerpt && (
                    <p>
                      <mark>{current.excerpt}</mark>
                    </p>
                  )}
                  {snapshot?.text ? (
                    <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>{snapshot.text}</pre>
                  ) : (
                    <p className="secondary">—</p>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </main>
  );
}
