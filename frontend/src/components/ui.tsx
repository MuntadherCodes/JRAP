import { ReactNode } from 'react';

/** Status badge — color always paired with the text label, never color alone. */
export function Badge({ tone, children }: { tone: string; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

const outcomeTone: Record<string, string> = {
  PASS: 'ok',
  PASS_WITH_CAVEATS: 'warn',
  FAIL: 'bad',
  UNCLEAR: 'info',
};

export function OutcomeBadge({ outcome }: { outcome: string }) {
  return <Badge tone={outcomeTone[outcome] ?? 'info'}>{outcome.replace(/_/g, ' ')}</Badge>;
}

const severityTone: Record<string, string> = {
  CRITICAL: 'critical',
  HIGH: 'bad',
  MEDIUM: 'warn',
  LOW: 'ok',
  INFO: 'info',
};

export function SeverityBadge({ severity }: { severity: string }) {
  return <Badge tone={severityTone[severity] ?? 'info'}>{severity}</Badge>;
}

const statusTone: Record<string, string> = {
  AUTO: 'info',
  NEEDS_VERIFICATION: 'warn',
  NEEDS_REVIEW: 'warn',
  CONFIRMED: 'ok',
  REJECTED: 'bad',
  EXCLUDED: 'info',
  COMPLETE: 'ok',
  RUNNING: 'brand',
  PENDING: 'info',
  FAILED: 'bad',
  CANCELLED: 'info',
  DRAFT: 'warn',
  RELEASED: 'ok',
  OPEN: 'info',
  IN_PROGRESS: 'brand',
  DONE: 'ok',
  ACTIVE: 'ok',
  ARCHIVED: 'info',
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={statusTone[status] ?? 'info'}>{status.replace(/_/g, ' ')}</Badge>;
}

/** The audit pipeline stepper (SRS §4). Report stages collapse into one "REPORT" step. */
const PIPELINE = ['CRAWL', 'EXTRACT', 'ENRICH', 'ANALYSE', 'REVIEW', 'REPORT'];
const REPORT_STAGES = ['DRAFT', 'GUARD', 'RELEASE'];

export function PipelineStepper({ stage, terminal }: { stage: string; terminal: boolean }) {
  const normalized = REPORT_STAGES.includes(stage) ? 'REPORT' : stage;
  const currentIndex = PIPELINE.indexOf(normalized);
  return (
    <div className="stepper" role="list">
      {PIPELINE.map((name, index) => {
        const done =
          index < currentIndex ||
          (terminal && index === currentIndex) ||
          (normalized === 'REPORT' && stage === 'RELEASE' && index === currentIndex);
        const current = index === currentIndex && !done;
        return (
          <span key={name} style={{ display: 'contents' }}>
            {index > 0 && <span className={`step-line ${index <= currentIndex ? 'done' : ''}`} />}
            <span className={`step ${done ? 'done' : current ? 'current' : ''}`} role="listitem">
              <span className="dot">{done ? '✓' : index + 1}</span>
              <span className="name">{name}</span>
            </span>
          </span>
        );
      })}
    </div>
  );
}

export function Loading() {
  return <div className="loading">Loading…</div>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}
