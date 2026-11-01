import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { FocusEvent, FormEvent, PointerEvent, ReactNode } from 'react';
import { api } from './api';
import type { ConfigStatus, Project, SchedulePayload, SyncConfig, SyncJob } from './types';

type Page = 'overview' | 'configs' | 'runs' | 'detail';
type Notice = { kind: 'success' | 'error'; text: string } | null;

const weekdays = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

function App() {
  const [page, setPage] = useState<Page>('overview');
  const [configs, setConfigs] = useState<SyncConfig[]>([]);
  const [recentJobs, setRecentJobs] = useState<SyncJob[]>([]);
  const [jobs, setJobs] = useState<SyncJob[]>([]);
  const [detailJobs, setDetailJobs] = useState<SyncJob[]>([]);
  const [detailCursor, setDetailCursor] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [nextScheduledRuns, setNextScheduledRuns] = useState<Record<string, string>>({});
  const [runCursor, setRunCursor] = useState<string | null>(null);
  const [runLoading, setRunLoading] = useState(false);
  const [selected, setSelected] = useState<SyncConfig | null>(null);
  const [editor, setEditor] = useState<{ mode: 'create' | 'edit'; config?: SyncConfig } | null>(null);
  const [manualOpen, setManualOpen] = useState(false);
  const [projectSync, setProjectSync] = useState<SyncConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<Notice>(null);
  const [health, setHealth] = useState('CHECKING');
  const [runStatus, setRunStatus] = useState('');
  const [runTrigger, setRunTrigger] = useState('');
  const [tooltipTarget, setTooltipTarget] = useState<HTMLElement | null>(null);
  const hoveredTooltip = useRef<HTMLElement | null>(null);
  const focusedTooltip = useRef<HTMLElement | null>(null);
  const dataRequestVersion = useRef(0);
  const runRequestVersion = useRef(0);
  const detailRequestVersion = useRef(0);

  function updateTooltipTarget() {
    setTooltipTarget(hoveredTooltip.current || focusedTooltip.current);
  }

  function findTooltipTarget(target: EventTarget | null) {
    return target instanceof Element ? target.closest<HTMLElement>('[data-tooltip]') : null;
  }

  function handlePointerOver(event: PointerEvent<HTMLDivElement>) {
    const target = findTooltipTarget(event.target);
    if (!target?.dataset.tooltip) return;
    hoveredTooltip.current = target;
    updateTooltipTarget();
  }

  function handlePointerOut(event: PointerEvent<HTMLDivElement>) {
    const target = findTooltipTarget(event.target);
    const related = event.relatedTarget instanceof Node ? event.relatedTarget : null;
    if (!target || target.contains(related) || target.matches(':focus-within')) return;
    if (hoveredTooltip.current === target) {
      hoveredTooltip.current = null;
      updateTooltipTarget();
    }
  }

  function handleFocus(event: FocusEvent<HTMLDivElement>) {
    const target = findTooltipTarget(event.target);
    if (!target?.dataset.tooltip) return;
    focusedTooltip.current = target;
    updateTooltipTarget();
  }

  function handleBlur(event: FocusEvent<HTMLDivElement>) {
    const target = findTooltipTarget(event.target);
    const related = event.relatedTarget instanceof Node ? event.relatedTarget : null;
    if (!target || target.contains(related) || target.matches(':hover')) return;
    if (focusedTooltip.current === target) {
      focusedTooltip.current = null;
      updateTooltipTarget();
    }
  }

  const loadData = useCallback(async () => {
    const requestVersion = ++dataRequestVersion.current;
    setLoading(true);
    const [healthResult, configsResult, jobsResult, nextRunsResult] = await Promise.allSettled([
      api.health(), api.listConfigs(), api.listJobs({}, undefined, 5), api.nextScheduledRuns(),
    ]);
    if (requestVersion !== dataRequestVersion.current) return;
    setHealth(healthResult.status === 'fulfilled' ? healthResult.value.status : 'OFFLINE');
    if (configsResult.status === 'fulfilled') {
      setConfigs(configsResult.value);
      setSelected((current) => current ? configsResult.value.find((config) => config.config_id === current.config_id) || current : null);
    }
    if (jobsResult.status === 'fulfilled') setRecentJobs(jobsResult.value.items);
    if (nextRunsResult.status === 'fulfilled') setNextScheduledRuns(nextRunsResult.value);
    const failed = [configsResult, jobsResult, nextRunsResult].find((result) => result.status === 'rejected');
    if (failed?.status === 'rejected') setNotice({ kind: 'error', text: errorText(failed.reason) });
    setLoading(false);
  }, []);

  const loadRuns = useCallback(async () => {
    const requestVersion = ++runRequestVersion.current;
    try {
      const filters: Record<string, string> = {};
      if (runStatus) filters.status = runStatus;
      if (runTrigger) filters.trigger_type = runTrigger;
      setRunLoading(true);
      const result = await api.listJobs(filters);
      if (requestVersion !== runRequestVersion.current) return;
      setJobs(result.items);
      setRunCursor(result.next_before_id);
    } catch (error) {
      if (requestVersion === runRequestVersion.current) setNotice({ kind: 'error', text: errorText(error) });
    } finally {
      if (requestVersion === runRequestVersion.current) setRunLoading(false);
    }
  }, [runStatus, runTrigger]);

  const loadMoreRuns = useCallback(async () => {
    if (!runCursor) return;
    const requestVersion = ++runRequestVersion.current;
    const cursor = runCursor;
    try {
      setRunLoading(true);
      const filters: Record<string, string> = {};
      if (runStatus) filters.status = runStatus;
      if (runTrigger) filters.trigger_type = runTrigger;
      const result = await api.listJobs(filters, cursor);
      if (requestVersion !== runRequestVersion.current) return;
      setJobs((current) => [...current, ...result.items]);
      setRunCursor(result.next_before_id);
    } catch (error) {
      if (requestVersion === runRequestVersion.current) setNotice({ kind: 'error', text: errorText(error) });
    } finally {
      if (requestVersion === runRequestVersion.current) setRunLoading(false);
    }
  }, [runCursor, runStatus, runTrigger]);

  useEffect(() => { void loadData(); }, [loadData]);
  useEffect(() => { if (page === 'runs') void loadRuns(); }, [page, loadRuns]);
  useEffect(() => {
    if (page !== 'detail' || !selected) return;
    void loadDetailRuns(selected.config_id);
    return () => {
      detailRequestVersion.current++;
    };
  }, [page, selected?.config_id]);

  async function loadDetailRuns(configId: string) {
    const requestVersion = ++detailRequestVersion.current;
    setDetailJobs([]);
    setDetailCursor(null);
    setDetailLoading(true);
    try {
      const result = await api.listConfigJobs(configId);
      if (requestVersion !== detailRequestVersion.current) return;
      setDetailJobs(result.items);
      setDetailCursor(result.next_before_id);
    } catch (error) {
      if (requestVersion === detailRequestVersion.current) setNotice({ kind: 'error', text: errorText(error) });
    } finally {
      if (requestVersion === detailRequestVersion.current) setDetailLoading(false);
    }
  }

  async function loadMoreConfigRuns() {
    if (!selected || !detailCursor) return;
    const requestVersion = ++detailRequestVersion.current;
    const configId = selected.config_id;
    const cursor = detailCursor;
    setDetailLoading(true);
    try {
      const result = await api.listConfigJobs(configId, cursor);
      if (requestVersion !== detailRequestVersion.current) return;
      setDetailJobs((current) => [...current, ...result.items]);
      setDetailCursor(result.next_before_id);
    } catch (error) {
      if (requestVersion === detailRequestVersion.current) setNotice({ kind: 'error', text: errorText(error) });
    } finally {
      if (requestVersion === detailRequestVersion.current) setDetailLoading(false);
    }
  }

  const nextRuns = new Map(Object.entries(nextScheduledRuns));
  let title = selected?.project.name || 'Configuration';
  if (page === 'overview') title = 'Overview';
  else if (page === 'configs') title = 'Configurations';
  else if (page === 'runs') title = 'Run history';

  let serviceLabel = 'unavailable';
  if (health === 'UP') serviceLabel = 'online';
  else if (health === 'CHECKING') serviceLabel = 'checking';

  function showNotice(kind: 'success' | 'error', text: string) {
    setNotice({ kind, text });
    window.setTimeout(() => setNotice(null), 5000);
  }

  async function refreshCurrentView() {
    const refreshes = [loadData()];
    if (page === 'runs') refreshes.push(loadRuns());
    if (page === 'detail' && selected) refreshes.push(loadDetailRuns(selected.config_id));
    await Promise.all(refreshes);
  }

  async function afterMutation(message: string) {
    setEditor(null);
    setManualOpen(false);
    setProjectSync(null);
    showNotice('success', message);
    await refreshCurrentView();
  }

  function queueProject(config: SyncConfig) {
    setProjectSync(config);
  }

  function openConfig(config: SyncConfig) {
    setSelected(config);
    setPage('detail');
  }

  return (
    <div className="app-shell" onPointerOverCapture={handlePointerOver} onPointerOutCapture={handlePointerOut} onFocusCapture={handleFocus} onBlurCapture={handleBlur}>
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">↗</span><span>relay<span className="brand-dot">.</span></span></div>
        <div className="workspace-label">SERVICE</div>
        <div className="workspace-switch"><span className="workspace-avatar">P</span><span><b>Plane → Notion</b><small>Local integration service</small></span></div>
        <div className="nav-label">MONITOR</div>
        <nav className="nav-list">
          <NavButton icon="◫" label="Overview" active={page === 'overview'} onClick={() => setPage('overview')} />
          <NavButton icon="▤" label="Configurations" count={configs.length} active={page === 'configs' || page === 'detail'} onClick={() => setPage('configs')} />
          <NavButton icon="◷" label="Run history" active={page === 'runs'} onClick={() => setPage('runs')} />
        </nav>
        <div className="sidebar-bottom">
          <div className="connection-card">
            <div className="connection-title"><span className={`status-dot ${health === 'UP' ? 'online' : ''}`} /> Service {serviceLabel}</div>
            <p>Plane and Notion credentials are managed by the local service.</p>
          </div>
          <div className="profile"><div className="profile-avatar">V</div><span><b>Local operator</b><small>Admin access</small></span><span className="more">···</span></div>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumbs"><span>Integrations</span><span className="crumb-sep">/</span><b>{title}</b></div>
          <div className="top-actions"><span className="environment"><i /> LOCAL ENVIRONMENT</span><button className="icon-button" aria-label="Refresh current view" data-tooltip="Reload this view and its latest sync data." onClick={() => void refreshCurrentView()}>↻</button><button className="button button-primary button-small" data-tooltip="Queue a sync for one Plane task URL." onClick={() => setManualOpen(true)}><span>＋</span> Sync a task</button></div>
        </header>

        {notice && <div className={`toast ${notice.kind}`} role="status"><span>{notice.kind === 'success' ? '✓' : '!'}</span>{notice.text}<button aria-label="Dismiss message" data-tooltip="Dismiss this message." onClick={() => setNotice(null)}>×</button></div>}

        <div className={`page-content ${page === 'configs' || page === 'runs' ? 'page-content-fixed' : ''} ${page === 'configs' ? 'page-content-configs' : ''}`}>
          {page === 'overview' && <Overview configs={configs} jobs={recentJobs} nextRuns={nextRuns} loading={loading} onNavigate={setPage} onOpen={openConfig} />}
          {page === 'configs' && <Configurations configs={configs} nextRuns={nextRuns} loading={loading} onCreate={() => setEditor({ mode: 'create' })} onEdit={(config) => setEditor({ mode: 'edit', config })} onOpen={openConfig} onSync={queueProject} />}
          {page === 'runs' && <RunHistory jobs={jobs} configs={configs} status={runStatus} trigger={runTrigger} onStatus={setRunStatus} onTrigger={setRunTrigger} loading={runLoading || loading} nextBeforeId={runCursor} onLoadMore={() => void loadMoreRuns()} />}
          {page === 'detail' && selected && <ConfigDetail config={selected} jobs={detailJobs} nextRun={nextRuns.get(selected.config_id) || null} loading={detailLoading} nextBeforeId={detailCursor} onLoadMore={loadMoreConfigRuns} onBack={() => setPage('configs')} onEdit={() => setEditor({ mode: 'edit', config: selected })} onSync={() => queueProject(selected)} />}
        </div>
      </main>

      {editor && <ConfigEditor mode={editor.mode} config={editor.config} onClose={() => setEditor(null)} onSaved={() => void afterMutation(editor.mode === 'create' ? 'Configuration created.' : 'Configuration updated.')} notify={showNotice} />}
      {manualOpen && <ManualSyncDialog onClose={() => setManualOpen(false)} onQueued={() => void afterMutation('Manual sync queued.')} notify={showNotice} />}
      {projectSync && <ManualProjectSyncDialog config={projectSync} onClose={() => setProjectSync(null)} onQueued={() => void afterMutation('Project sync queued.')} notify={showNotice} />}
      {tooltipTarget?.isConnected && tooltipTarget.dataset.tooltip && <FloatingTooltip target={tooltipTarget} text={tooltipTarget.dataset.tooltip} />}
    </div>
  );
}

function FloatingTooltip({ target, text }: { target: HTMLElement; text: string }) {
  const tooltipId = useId();
  const tooltipRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ left: number; top: number } | null>(null);

  useEffect(() => {
    const previousDescription = target.getAttribute('aria-describedby');
    const descriptions = new Set((previousDescription || '').split(/\s+/).filter(Boolean));
    descriptions.add(tooltipId);
    target.setAttribute('aria-describedby', [...descriptions].join(' '));
    return () => {
      if (previousDescription) target.setAttribute('aria-describedby', previousDescription);
      else target.removeAttribute('aria-describedby');
    };
  }, [target, tooltipId]);

  useLayoutEffect(() => {
    const placeTooltip = () => {
      const tooltip = tooltipRef.current;
      if (!tooltip || !target.isConnected) return;

      const targetRect = target.getBoundingClientRect();
      const tooltipRect = tooltip.getBoundingClientRect();
      const margin = 10;
      const gap = 8;
      const maxLeft = Math.max(margin, window.innerWidth - tooltipRect.width - margin);
      const left = Math.min(maxLeft, Math.max(margin, targetRect.left + targetRect.width / 2 - tooltipRect.width / 2));
      const above = targetRect.top - tooltipRect.height - gap;
      const below = targetRect.bottom + gap;
      const top = above >= margin
        ? above
        : below + tooltipRect.height <= window.innerHeight - margin
          ? below
          : Math.max(margin, window.innerHeight - tooltipRect.height - margin);

      setPosition({ left, top });
    };

    placeTooltip();
    window.addEventListener('resize', placeTooltip);
    window.addEventListener('scroll', placeTooltip, true);
    return () => {
      window.removeEventListener('resize', placeTooltip);
      window.removeEventListener('scroll', placeTooltip, true);
    };
  }, [target, text]);

  return createPortal(
    <div id={tooltipId} ref={tooltipRef} className="app-tooltip" role="tooltip" style={{ left: position?.left ?? 0, top: position?.top ?? 0, visibility: position ? 'visible' : 'hidden' }}>
      {text}
    </div>,
    document.body,
  );
}

function NavButton({ icon, label, active, count, onClick }: { icon: string; label: string; active: boolean; count?: number; onClick: () => void }) {
  return <button className={`nav-item ${active ? 'active' : ''}`} onClick={onClick}><span className="nav-icon">{icon}</span><span>{label}</span>{count !== undefined && <span className="nav-count">{count}</span>}</button>;
}

function Overview({ configs, jobs, nextRuns, loading, onNavigate, onOpen }: {
  configs: SyncConfig[]; jobs: SyncJob[]; nextRuns: Map<string, string | null>; loading: boolean;
  onNavigate: (page: Page) => void; onOpen: (config: SyncConfig) => void;
}) {
  const successful = jobs.filter((job) => job.status === 'SUCCESS').length;
  const failed = jobs.filter((job) => job.status === 'FAILED').length;
  const completedRuns = successful + failed;
  const rate = completedRuns ? Math.round(successful / completedRuns * 100) : null;
  const active = configs.filter((config) => config.status === 'ACTIVE');
  const totalChanged = jobs.reduce((sum, job) => sum + job.created_count + job.updated_count, 0);
  const latest = [...jobs].sort((a, b) => b.created_at.localeCompare(a.created_at)).slice(0, 5);
  const chartJobs = [...jobs].reverse();
  const firstRun = chartJobs[0];
  const lastRun = chartJobs[chartJobs.length - 1];
  let rateValue = '—';
  if (!loading && rate !== null) rateValue = `${rate}%`;

  return <>
    <section className="page-heading heading-row"><div><div className="eyebrow">SYNC OPERATIONS <span className="eyebrow-line" /></div><h1>Good to see you<span className="heading-period">.</span></h1><p className="subtitle">Copy active work items from Plane (a project tracker) into a separate Notion database. Scheduled runs keep it updated; “Sync now” starts a full sync immediately.</p></div><button className="button button-secondary" data-tooltip="Open the project configuration list." onClick={() => onNavigate('configs')}>Manage configs <span>→</span></button></section>
    <section className="stat-grid">
      <StatCard label="Active configurations" value={loading ? '—' : `${active.length}`} detail={`${configs.length} total projects`} accent="mint" icon="▤" />
      <StatCard label="Run success rate" value={rateValue} detail={`${successful} of ${completedRuns} completed · latest ${jobs.length} runs`} accent="lavender" icon="✓" />
      <StatCard label="Items created or updated" value={loading ? '—' : totalChanged.toLocaleString()} detail={`Across latest ${jobs.length} runs`} accent="peach" icon="↗" />
    </section>
    <section className="overview-grid">
      <div className="panel config-panel"><div className="panel-heading"><div><h2>Connected projects</h2><p>Your configured Plane projects and their next run.</p></div><button className="text-button" data-tooltip="View all project configurations." onClick={() => onNavigate('configs')}>All configs <span>→</span></button></div>
        {configs.length === 0 && !loading ? <EmptyState title="No projects connected" text="Create a config to start syncing a Plane project into a Notion database." action="Create first config" onClick={() => onNavigate('configs')} /> : <div className="project-list">{configs.slice(0, 4).map((config) => <button className="project-row" data-tooltip={`View sync settings and runs for ${config.project.name}.`} key={config.config_id} onClick={() => onOpen(config)}><ProjectMark name={config.project.name} /><span className="project-main"><b>{config.project.name}</b><ProjectSourceMeta project={config.project} /></span><span className="schedule-cell"><small>NEXT RUN</small><b>{nextRuns.get(config.config_id) ? formatDate(nextRuns.get(config.config_id)!) : config.status === 'ACTIVE' ? loading ? 'Checking…' : 'No run scheduled' : 'No run scheduled'}</b></span><StatusPill status={config.status} /></button>)}</div>}
      </div>
      <div className="panel health-panel"><div className="panel-heading"><div><h2>Sync pulse</h2><p>{chartJobs.length ? `Latest ${chartJobs.length} runs, oldest to newest` : 'No recent runs'}</p></div><span className="pulse-icon">⌁</span></div>
        <div className="pulse-summary"><div className="pulse-number">{jobs.length}</div><div><b>recent runs shown</b><span>Latest activity across all configs</span></div></div>
        <div className="mini-bars" aria-label={`Latest ${chartJobs.length} runs, oldest to newest`}>
          {chartJobs.length ? chartJobs.map((run) => {
            let height = 19;
            if (run.status === 'SUCCESS') height = 38 + Math.min(55, run.created_count + run.updated_count * 2);
            else if (run.status === 'FAILED') height = 26;
            const tooltip = `${statusLabel(run.status)} ${run.trigger_type.toLowerCase()} run · ${formatDate(run.created_at)} · ${run.eligible_count} eligible · ${run.created_count} created · ${run.updated_count} updated`;
            return <span key={run.job_id} className={run.status === 'SUCCESS' ? 'successful' : 'other'} data-tooltip={tooltip} style={{ height: `${height}%` }} />;
          }) : <div className="mini-bars-empty">No runs yet</div>}
        </div>
        {firstRun && lastRun && <div className="mini-bars-axis"><span>{formatDate(firstRun.created_at)}</span><span>{formatDate(lastRun.created_at)}</span></div>}
        <div className="pulse-legend"><span><i className="legend-dot green" />Successful</span><span><i className="legend-dot gray" />Queued / other</span></div>
        <button className="text-button pulse-link" data-tooltip="Open the complete sync run history." onClick={() => onNavigate('runs')}>View run history <span>→</span></button>
      </div>
    </section>
    <section className="panel recent-panel"><div className="panel-heading"><div><h2>Recent runs</h2><p>Jobs created by your schedules and manual requests.</p></div><button className="text-button" data-tooltip="Open all scheduled and on-demand runs." onClick={() => onNavigate('runs')}>See all runs <span>→</span></button></div><JobsTable jobs={latest} configs={configs} loading={loading} /></section>
  </>;
}

function StatCard({ label, value, detail, accent, icon }: { label: string; value: string; detail: string; accent: string; icon: string }) {
  return <div className="stat-card"><div className="stat-top"><span>{label}</span><span className={`stat-icon ${accent}`}>{icon}</span></div><div className="stat-value">{value}</div><div className="stat-detail"><span className="tiny-check">↗</span>{detail}</div></div>;
}

function Configurations({ configs, nextRuns, loading, onCreate, onEdit, onOpen, onSync }: {
  configs: SyncConfig[]; nextRuns: Map<string, string | null>; loading: boolean;
  onCreate: () => void; onEdit: (config: SyncConfig) => void; onOpen: (config: SyncConfig) => void; onSync: (config: SyncConfig) => void;
}) {
  return <>
    <section className="page-heading heading-row"><div><div className="eyebrow">PROJECT SYNC SETTINGS <span className="eyebrow-line" /></div><h1>Configurations<span className="heading-period">.</span></h1><p className="subtitle">Choose a source project in Plane (project tracker), copy its work items to a Notion database, and set a schedule.</p></div><button className="button button-primary" data-tooltip="Create a new project sync configuration." onClick={onCreate}><span>＋</span> New configuration</button></section>
    <div className="panel table-panel"><div className="table-toolbar"><div className="toolbar-caption"><span className="toolbar-square">▤</span><b>Project connections</b><span className="count-badge">{configs.length}</span></div><span className="muted-inline">Each source project has its own Notion database</span></div>
      {configs.length === 0 && !loading ? <EmptyState title="Your config list is empty" text="Choose a source workspace and project. The service creates a Notion database for it and applies the schedule you choose." action="Create configuration" onClick={onCreate} /> : <div className="table-scroll"><table><thead><tr><th scope="col">SOURCE PROJECT</th><th scope="col">STATUS</th><th scope="col">SCHEDULE</th><th scope="col">NEXT RUN · LOCAL TIME</th><th scope="col">CONFIG EDITED</th><th scope="col">ACTIONS</th></tr></thead><tbody>{configs.map((config) => <tr key={config.config_id}><td><button className="table-project" data-tooltip={`Open sync details for ${config.project.name}.`} onClick={() => onOpen(config)}><ProjectMark name={config.project.name} small /><span><b>{config.project.name}</b><ProjectSourceMeta project={config.project} /></span></button></td><td><StatusPill status={config.status} /></td><td title={config.schedule_type === 'CALENDAR' ? 'Calendar schedules use UTC.' : undefined}><span className="schedule-label"><span>◷</span>{scheduleLabel(config)}</span></td><td title={nextRuns.get(config.config_id) ? formatExactDate(nextRuns.get(config.config_id)!) : undefined}>{nextRuns.get(config.config_id) ? formatDate(nextRuns.get(config.config_id)!) : <span className="muted">No run scheduled</span>}</td><td title={formatExactDate(config.updated_at)}>{formatRelative(config.updated_at)}</td><td><div className="row-actions"><button aria-label={`Sync ${config.project.name} now`} data-tooltip="Start a full on-demand sync for this project." onClick={() => onSync(config)}>↻ <span>Sync now</span></button><button aria-label={`Edit ${config.project.name} configuration`} data-tooltip="Change this project's sync options or schedule." onClick={() => onEdit(config)}>··· <span>Edit</span></button></div></td></tr>)}</tbody></table></div>}
      {loading && <div className="loading-line">Loading configurations…</div>}
    </div>
    <div className="info-banner"><span className="info-symbol">i</span><span>Each configuration copies active, non-draft work items from one source project into its own Notion database. <b>Disabled</b> keeps the settings but pauses scheduled syncs; <b>Archived</b> marks a configuration as no longer in use. Select <b>Sync now</b> to start a full sync manually.</span></div>
  </>;
}

function RunHistory({ jobs, configs, status, trigger, onStatus, onTrigger, loading, nextBeforeId, onLoadMore }: {
  jobs: SyncJob[]; configs: SyncConfig[]; status: string; trigger: string;
  onStatus: (value: string) => void; onTrigger: (value: string) => void; loading: boolean;
  nextBeforeId: string | null; onLoadMore: () => void;
}) {
  return <>
    <section className="page-heading heading-row"><div><div className="eyebrow">RUN REPORTING <span className="eyebrow-line" /></div><h1>Run history<span className="heading-period">.</span></h1><p className="subtitle">See which project ran, when it ran in your local time, and how many work items changed.</p></div><span className="history-count"><b>{jobs.length}</b> shown</span></section>
    <div className="panel table-panel"><div className="table-toolbar"><div className="toolbar-caption"><span className="toolbar-square purple">◷</span><b>All sync runs</b><span className="count-badge">{jobs.length}</span></div><div className="filter-controls"><label><span className="sr-only">Filter by status</span><select value={status} onChange={(event) => onStatus(event.target.value)}><option value="">All statuses</option>{['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED_OVERLAP'].map((item) => <option key={item} value={item}>{statusLabel(item)}</option>)}</select></label><label><span className="sr-only">Filter by trigger</span><select value={trigger} onChange={(event) => onTrigger(event.target.value)}><option value="">All triggers</option><option value="SCHEDULED">Scheduled</option><option value="MANUAL">Manual</option></select></label></div></div><JobsTable jobs={jobs} configs={configs} loading={loading} />{nextBeforeId && <div className="load-more"><button className="button button-secondary" data-tooltip="Load the next page of sync runs." disabled={loading} onClick={onLoadMore}>{loading ? 'Loading…' : 'Load more runs'}</button></div>}</div>
  </>;
}

function ConfigDetail({ config, jobs, nextRun, loading, nextBeforeId, onLoadMore, onBack, onEdit, onSync }: {
  config: SyncConfig; jobs: SyncJob[]; nextRun: string | null; loading: boolean; nextBeforeId: string | null;
  onLoadMore: () => void; onBack: () => void; onEdit: () => void; onSync: () => void;
}) {
  const [copyFeedback, setCopyFeedback] = useState<{ key: string; result: 'copied' | 'failed' } | null>(null);

  async function copyDestinationId(key: string, value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopyFeedback({ key, result: 'copied' });
    } catch {
      setCopyFeedback({ key, result: 'failed' });
    }
    window.setTimeout(() => setCopyFeedback(null), 1600);
  }

  let nextRunLabel = 'No run scheduled';
  if (nextRun) nextRunLabel = formatDate(nextRun);

  return <>
    <button className="back-link" data-tooltip="Return to the configuration list." onClick={onBack}>← All configurations</button>
    <section className="page-heading detail-heading"><div className="detail-title"><ProjectMark name={config.project.name} /><div><div className="eyebrow">SOURCE PROJECT <span className="eyebrow-line" /></div><h1>{config.project.name}<span className="heading-period">.</span></h1><ProjectSourceMeta project={config.project} /><a href={config.project.project_url} target="_blank" rel="noreferrer">Open source project ↗</a></div></div><div className="heading-buttons"><button className="button button-secondary" data-tooltip="Change this project's sync settings." onClick={onEdit}>Edit configuration</button><button className="button button-primary" data-tooltip="Start a full on-demand sync for this project." onClick={onSync}>↻ Sync now</button></div></section>
    <section className="detail-stats"><div className="panel detail-stat"><small>CONFIGURATION STATUS</small><StatusPill status={config.status} /></div><div className="panel detail-stat"><small>SCHEDULE</small><b>◷ {scheduleLabel(config)}</b></div><div className="panel detail-stat"><small>NEXT RUN · LOCAL TIME</small><b>{nextRunLabel}</b></div><div className="panel detail-stat"><small>SYNC OPTIONS</small><b>Work items</b></div></section>
    <section className="panel recent-panel"><div className="panel-heading"><div><h2>Recent sync runs</h2><p>Each run shows its result and the number of work items created, changed, or unchanged.</p></div><span className="count-badge">{jobs.length}</span></div><JobsTable jobs={jobs} configs={[config]} loading={loading} />{nextBeforeId && <div className="load-more"><button className="button button-secondary" data-tooltip="Load the next page of this project's runs." disabled={loading} onClick={onLoadMore}>{loading ? 'Loading…' : 'Load more runs'}</button></div>}</section>
    <section className="panel destination-panel"><div className="destination-icon">N</div><div><h2>Notion destination</h2><p>Workspace page: <b>Plane Workspace — {config.project.workspace_slug}</b></p><p>Project page: <b>{config.project.name}</b> · Database: <b>Plane Work Items</b></p></div><div className="destination-ids">
      <DestinationIdentifier name="DATABASE ID" value={config.notion_database_id} feedback={copyFeedback?.key === 'database' ? copyFeedback.result : null} onCopy={() => void copyDestinationId('database', config.notion_database_id)} />
      <DestinationIdentifier name="DATA SOURCE ID" value={config.notion_data_source_id} feedback={copyFeedback?.key === 'dataSource' ? copyFeedback.result : null} onCopy={() => void copyDestinationId('dataSource', config.notion_data_source_id)} />
    </div></section>
  </>;
}

function DestinationIdentifier({ name, value, feedback, onCopy }: {
  name: string; value: string; feedback: 'copied' | 'failed' | null; onCopy: () => void;
}) {
  const buttonLabel = feedback === 'copied' ? 'Copied' : feedback === 'failed' ? 'Try again' : 'Copy';
  let buttonDescription = `Copy ${name.toLowerCase()} ID`;
  if (feedback === 'copied') buttonDescription = `${name.toLowerCase()} ID copied`;
  else if (feedback === 'failed') buttonDescription = `Could not copy ${name.toLowerCase()} ID`;
  return <span className="destination-id"><span>{name}</span><div className="destination-id-value"><code title={value}>{value}</code><button type="button" aria-label={buttonDescription} data-tooltip={buttonDescription} onClick={onCopy}>{buttonLabel}</button></div></span>;
}

function JobsTable({ jobs, configs, loading }: {
  jobs: SyncJob[]; configs: SyncConfig[]; loading?: boolean;
}) {
  const configById = new Map(configs.map((config) => [config.config_id, config]));
  if (loading && jobs.length === 0) return <div className="loading-line">Loading runs…</div>;
  if (jobs.length === 0) return <div className="table-empty"><span className="empty-icon">◷</span><b>No runs yet</b><span>Sync runs will appear here as soon as the worker processes a job.</span></div>;
  return <div className="table-scroll">
    <table>
      <thead><tr><th scope="col">RUN</th><th scope="col">PROJECT</th><th scope="col">TRIGGER</th><th scope="col">RUN TIME</th><th scope="col">RESULT</th><th scope="col">CHANGES</th></tr></thead>
      <tbody>{jobs.map((job) => {
        const config = job.config_id ? configById.get(job.config_id) : undefined;
        const changed = job.created_count + job.updated_count;
        const projectName = config?.project.name || (job.requested_task_url ? 'Manual task URL' : 'Unconfigured project');
        const triggerLabel = job.trigger_type === 'SCHEDULED' ? 'Scheduled' : 'Manual';
        let outcome = <span className="muted">—</span>;

        if (job.status === 'FAILED') {
          outcome = <span className="error-cell" title={job.error_message || ''}>
            {job.error_code || 'See details'}
            {job.eligible_count > 0 && <small> · {job.eligible_count} eligible</small>}
          </span>;
        } else if (job.status === 'SUCCESS') {
          outcome = <span className="changes-cell">
            {job.eligible_count} eligible · {changed} changed <small>· {job.unchanged_count} unchanged</small>
          </span>;
        } else if (job.status === 'SKIPPED_OVERLAP') {
          outcome = <span className="muted">Overlap</span>;
        }

        return <tr key={job.job_id}>
          <td><span className="run-id">#{job.job_id.slice(0, 8)}</span></td>
          <td><span className="run-project">{projectName}</span></td>
          <td><span className={`trigger-pill ${job.trigger_type.toLowerCase()}`}><i />{triggerLabel}</span></td>
          <td><span className="date-cell" title={formatExactDate(job.started_at || (job.status === 'PENDING' && job.trigger_type === 'SCHEDULED' ? job.scheduled_for : null) || job.created_at)}>{formatDate(job.started_at || (job.status === 'PENDING' && job.trigger_type === 'SCHEDULED' ? job.scheduled_for : null) || job.created_at)}</span></td>
          <td><StatusPill status={job.status} /></td>
          <td>{outcome}</td>
        </tr>;
      })}</tbody>
    </table>
  </div>;
}

function StatusPill({ status }: { status: string }) {
  const className = status.toLowerCase().replaceAll('_', '-');
  const descriptions: Record<string, string> = {
    ACTIVE: 'Scheduled syncs are enabled.',
    DISABLED: 'Settings are kept, but scheduled syncs are paused.',
    ARCHIVED: 'This configuration is no longer in use. Scheduled syncs are stopped.',
    PENDING: 'Waiting for the worker to start this sync.',
    RUNNING: 'The worker is processing this sync.',
    SUCCESS: 'This sync completed successfully.',
    FAILED: 'This sync failed. Check its run details for the error.',
    SKIPPED_OVERLAP: 'This scheduled occurrence was skipped because another sync was running.',
  };
  const description = descriptions[status] || `Current status: ${statusLabel(status)}.`;
  return <span className={`status-pill ${className}`} title={description} aria-label={`${statusLabel(status)}. ${description}`}><i />{statusLabel(status)}</span>;
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    ACTIVE: 'Active', DISABLED: 'Disabled', ARCHIVED: 'Archived',
    PENDING: 'Pending', RUNNING: 'Running', SUCCESS: 'Successful', FAILED: 'Failed',
    SKIPPED_OVERLAP: 'Skipped (overlap)',
  };
  return labels[status] || status.replaceAll('_', ' ').toLowerCase();
}

function ProjectMark({ name, small = false }: { name: string; small?: boolean }) {
  const initials = name.split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
  return <span className={`project-mark ${small ? 'small' : ''}`}>{initials || 'P'}</span>;
}

function ProjectSourceMeta({ project }: { project: Project }) {
  return <span className="project-source-meta">
    <span><small>Plane workspace slug</small><b>{project.workspace_slug}</b></span>
    <span><small>Project identifier</small><b>{project.identifier}</b></span>
  </span>;
}

function EmptyState({ title, text, action, onClick }: { title: string; text: string; action: string; onClick: () => void }) {
  return <div className="empty-state"><span className="empty-orbit">↗</span><h3>{title}</h3><p>{text}</p><button className="button button-secondary" data-tooltip={text} onClick={onClick}>{action} <span>→</span></button></div>;
}

function ConfigEditor({ mode, config, onClose, onSaved, notify }: {
  mode: 'create' | 'edit'; config?: SyncConfig; onClose: () => void;
  onSaved: () => void; notify: (kind: 'success' | 'error', text: string) => void;
}) {
  const [workspaceSlug, setWorkspaceSlug] = useState(config?.project.workspace_slug || '');
  const [projects, setProjects] = useState<Project[]>(config ? [config.project] : []);
  const [projectRef, setProjectRef] = useState(config?.project.plane_project_ref || '');
  const [projectUrl, setProjectUrl] = useState('');
  const [connection, setConnection] = useState('');
  const [scheduleType, setScheduleType] = useState(config?.schedule_type || 'INTERVAL');
  const [intervalValue, setIntervalValue] = useState(config?.interval_value || 30);
  const [intervalUnit, setIntervalUnit] = useState<'MINUTES' | 'HOURS'>(config?.interval_unit || 'MINUTES');
  const [frequency, setFrequency] = useState<'DAILY' | 'WEEKLY'>(config?.calendar_frequency || 'WEEKLY');
  const [calendarInterval, setCalendarInterval] = useState(config?.calendar_interval || 1);
  const [days, setDays] = useState<string[]>(config?.calendar_days_of_week || ['MONDAY']);
  const [calendarTime, setCalendarTime] = useState(config?.calendar_time_of_day?.slice(0, 5) || '09:00');
  const [status, setStatus] = useState<ConfigStatus>(config?.status || 'ACTIVE');
  const [startAt, setStartAt] = useState(toUtcInput(config?.start_at));
  const [endAt, setEndAt] = useState(toUtcInput(config?.end_at));
  const [saving, setSaving] = useState(false);
  const [calendarDaysError, setCalendarDaysError] = useState('');
  const invalidWeeklySchedule = scheduleType === 'CALENDAR' && frequency === 'WEEKLY' && days.length === 0;
  const connectionIsValid = connection.startsWith('Connected');

  async function testConnection() {
    setConnection('Testing…');
    try {
      await api.testPlaneConnection();
      setConnection('Connected');
    } catch (error) { setConnection(errorText(error)); }
  }

  async function loadProjects() {
    if (!workspaceSlug.trim()) return notify('error', 'Enter a Plane workspace slug first.');
    try {
      const response = await api.refreshProjects(workspaceSlug.trim());
      setProjects(response.projects);
      setProjectRef((current) => response.projects.some((project) => project.plane_project_ref === current) ? current : '');
      notify('success', `${response.projects.length} project${response.projects.length === 1 ? '' : 's'} loaded.`);
    } catch (error) { notify('error', errorText(error)); }
  }

  async function resolveUrl() {
    if (!projectUrl.trim()) return notify('error', 'Paste a Plane project URL first.');
    try {
      const project = await api.resolveProject(projectUrl.trim());
      setProjects((current) => [...current.filter((item) => item.plane_project_ref !== project.plane_project_ref), project]);
      setProjectRef(project.plane_project_ref);
      setWorkspaceSlug(project.workspace_slug);
      notify('success', `Project found: ${project.name}`);
    } catch (error) { notify('error', errorText(error)); }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (scheduleType === 'CALENDAR' && frequency === 'WEEKLY' && days.length === 0) {
      setCalendarDaysError('Select at least one day for a weekly schedule.');
      return;
    }
    setCalendarDaysError('');
    if (mode === 'create' && !projectRef) return notify('error', 'Select or resolve a Plane project first.');
    setSaving(true);
    const schedule: SchedulePayload = scheduleType === 'INTERVAL' ? {
      schedule_type: 'INTERVAL', interval_value: Number(intervalValue), interval_unit: intervalUnit,
      calendar_frequency: null, calendar_interval: null, calendar_days_of_week: [], calendar_time_of_day: null,
      start_at: fromUtcInput(startAt), end_at: fromUtcInput(endAt),
    } : {
      schedule_type: 'CALENDAR', interval_value: null, interval_unit: null,
      calendar_frequency: frequency, calendar_interval: Number(calendarInterval),
      calendar_days_of_week: frequency === 'WEEKLY' ? days : [], calendar_time_of_day: `${calendarTime}:00`,
      start_at: fromUtcInput(startAt), end_at: fromUtcInput(endAt),
    };
    const settings = { ...schedule, status, sync_options: { work_items: true, pages: false } };
    try {
      if (mode === 'create') await api.createConfig({ ...settings, plane_project_ref: projectRef });
      else if (config) await api.updateConfig(config.config_id, settings);
      onSaved();
    } catch (error) { notify('error', errorText(error)); }
    finally { setSaving(false); }
  }

  function toggleDay(day: string) {
    setDays((current) => current.includes(day) ? current.filter((item) => item !== day) : [...current, day]);
    setCalendarDaysError('');
  }

  return <Modal onClose={onClose} wide stickyFooter={mode === 'create'}><form className="editor-form" onSubmit={save}><div className="editor-scroll-content">
    <div className="modal-heading"><div><div className="eyebrow">{mode === 'create' ? 'NEW CONNECTION' : 'EDIT CONNECTION'} <span className="eyebrow-line" /></div><h2>{mode === 'create' ? 'Connect a project' : 'Configuration settings'}</h2><p>Choose what to copy from the project tracker to its Notion database, and how often.</p></div><button className="modal-close" aria-label="Close dialog" data-tooltip="Close configuration dialog." type="button" onClick={onClose}>×</button></div>
    {mode === 'create' && <section className="form-section"><div className="section-title"><span className="section-number">01</span><div><b>Source workspace and project</b><small>Choose a workspace, then select the project to sync.</small></div></div>
      <div className="connection-row"><button type="button" data-tooltip="Check the configured Plane API connection." className="button button-secondary" onClick={() => void testConnection()}>Test Plane connection <span>↗</span></button><span className={`connection-result ${connectionIsValid ? 'ok' : ''}`}>{connection || 'Uses the server-side connection'}</span></div>
      <div className="form-grid project-lookup"><label className="field"><span>Workspace slug</span><div className="input-with-button"><input value={workspaceSlug} onChange={(event) => setWorkspaceSlug(event.target.value)} placeholder="e.g. acme" /><button type="button" data-tooltip="Load projects from this workspace." className="button button-secondary button-small" onClick={() => void loadProjects()}>Load projects</button></div><small>The slug is the workspace identifier in its Plane URL, for example app.plane.so/<b>acme</b>/projects.</small></label><label className="field"><span>Or paste a project URL</span><div className="input-with-button"><input value={projectUrl} onChange={(event) => setProjectUrl(event.target.value)} placeholder="https://app.plane.so/acme/projects/…" /><button type="button" data-tooltip="Find the workspace and project from this URL." className="button button-secondary button-small" onClick={() => void resolveUrl()}>Resolve</button></div><small>The project URL identifies both the source workspace and project.</small></label></div>
      <label className="field project-select"><span>Source project</span><select value={projectRef} onChange={(event) => setProjectRef(event.target.value)}><option value="">Select a project…</option>{projects.filter((project) => project.is_available && !project.is_archived).map((project) => <option key={project.plane_project_ref} value={project.plane_project_ref}>{project.name} · Workspace slug: {project.workspace_slug} · Project identifier: {project.identifier}</option>)}</select></label>
    </section>}
    {mode === 'edit' && config && <section className="form-section existing-project"><ProjectMark name={config.project.name} /><div><b>{config.project.name}</b><ProjectSourceMeta project={config.project} /></div><StatusPill status={config.status} /></section>}
    <section className="form-section"><div className="section-title"><span className="section-number">02</span><div><b>Sync options</b><small>Choose the Plane data copied to Notion.</small></div></div><div className="option-cards"><label className="option-card chosen"><input type="checkbox" checked readOnly /><span className="option-check">✓</span><span><b>Work items</b><small>Active, non-draft tasks, status, priority, labels and dates</small></span><span className="option-state">INCLUDED</span></label><div className="option-card disabled"><span className="empty-check" /><span><b>Pages</b><small>Project pages and documentation</small></span><span className="option-state">COMING LATER</span></div></div></section>
    <section className="form-section"><div className="section-title"><span className="section-number">03</span><div><b>Schedule</b><small>Schedules are evaluated in UTC in this prototype.</small></div></div>
      <div className="form-grid schedule-top"><label className="field"><span>Schedule type</span><select value={scheduleType} onChange={(event) => setScheduleType(event.target.value as 'INTERVAL' | 'CALENDAR')}><option value="INTERVAL">Fixed interval</option><option value="CALENDAR">Calendar schedule</option></select></label><label className="field"><span>Config status</span><select value={status} onChange={(event) => setStatus(event.target.value as ConfigStatus)}><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option><option value="ARCHIVED">Archived</option></select></label></div>
      {scheduleType === 'INTERVAL' ? <div className="interval-fields"><label className="field"><span>Run every</span><input type="number" min="1" max="10000" value={intervalValue} onChange={(event) => setIntervalValue(Number(event.target.value))} /></label><label className="field"><span>Unit</span><select value={intervalUnit} onChange={(event) => setIntervalUnit(event.target.value as 'MINUTES' | 'HOURS')}><option value="MINUTES">Minutes</option><option value="HOURS">Hours</option></select></label></div> : <div className="calendar-fields"><div className="interval-fields"><label className="field"><span>Repeat</span><select value={frequency} onChange={(event) => { setFrequency(event.target.value as 'DAILY' | 'WEEKLY'); setCalendarDaysError(''); }}><option value="DAILY">Daily</option><option value="WEEKLY">Weekly</option></select></label><label className="field"><span>Every</span><div className="input-suffix"><input type="number" min="1" max="52" value={calendarInterval} onChange={(event) => setCalendarInterval(Number(event.target.value))} /><span>{frequency === 'WEEKLY' ? 'week(s)' : 'day(s)'}</span></div></label><label className="field"><span>At (UTC)</span><input type="time" value={calendarTime} onChange={(event) => setCalendarTime(event.target.value)} /></label></div>{frequency === 'WEEKLY' && <div className="field"><span>Days of week</span><div className="day-picker">{weekdays.map((day) => <button key={day} type="button" data-tooltip={`Toggle ${day}.`} aria-pressed={days.includes(day)} className={days.includes(day) ? 'selected' : ''} onClick={() => toggleDay(day)}>{day.slice(0, 3)}</button>)}</div>{(calendarDaysError || invalidWeeklySchedule) && <small className="field-error" role="alert">{calendarDaysError || 'Select at least one day for a weekly schedule.'}</small>}</div>}</div>}
      <div className="form-grid window-fields"><label className="field"><span>Start time <em>OPTIONAL</em></span><input type="datetime-local" value={startAt} onChange={(event) => setStartAt(event.target.value)} /><small>Inclusive · UTC</small></label><label className="field"><span>End time <em>OPTIONAL</em></span><input type="datetime-local" value={endAt} onChange={(event) => setEndAt(event.target.value)} /><small>Exclusive · UTC</small></label></div>
    </section>
    </div>
    <div className="modal-footer"><span>{mode === 'create' ? 'The Notion database is created automatically. The first scheduled sync runs at its next scheduled time; use Sync now to start immediately.' : 'Changes to the schedule apply to future runs.'}</span><div><button type="button" data-tooltip="Close without saving changes." className="button button-secondary" onClick={onClose}>Cancel</button><button type="submit" data-tooltip={mode === 'create' ? 'Create this sync configuration.' : 'Save configuration changes.'} className="button button-primary" disabled={saving || invalidWeeklySchedule}>{saving ? 'Saving…' : mode === 'create' ? 'Create configuration' : 'Save changes'} <span>→</span></button></div></div>
  </form></Modal>;
}

function ManualProjectSyncDialog({ config, onClose, onQueued, notify }: {
  config: SyncConfig; onClose: () => void; onQueued: () => void;
  notify: (kind: 'success' | 'error', text: string) => void;
}) {
  const [force, setForce] = useState(false);
  const [saving, setSaving] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    try { await api.enqueueProjectSync(config.config_id, force); onQueued(); }
    catch (error) { notify('error', errorText(error)); }
    finally { setSaving(false); }
  }
  return <Modal onClose={onClose}><form onSubmit={submit}>
    <div className="modal-heading"><div><div className="eyebrow">ON-DEMAND SYNC <span className="eyebrow-line" /></div><h2>Sync project work items</h2><p>Queue a full work-item sync for {config.project.name}.</p></div><button className="modal-close" aria-label="Close dialog" data-tooltip="Close project sync dialog." type="button" onClick={onClose}>×</button></div>
    <label className="force-option"><input type="checkbox" checked={force} onChange={(event) => setForce(event.target.checked)} /><span><b>Force run on archived config</b><small>Allows a one-time run when this configuration is archived.</small></span></label>
    <div className="modal-footer"><span>Manual requests queue behind an active sync.</span><div><button type="button" data-tooltip="Close without queueing a sync." className="button button-secondary" onClick={onClose}>Cancel</button><button type="submit" data-tooltip="Queue a full sync for this project." className="button button-primary" disabled={saving}>{saving ? 'Queueing…' : 'Queue sync'} <span>→</span></button></div></div>
  </form></Modal>;
}

function ManualSyncDialog({ onClose, onQueued, notify }: {
  onClose: () => void; onQueued: () => void; notify: (kind: 'success' | 'error', text: string) => void;
}) {
  const [taskUrl, setTaskUrl] = useState('');
  const [force, setForce] = useState(false);
  const [saving, setSaving] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    try { await api.enqueueTaskSync(taskUrl.trim(), force); onQueued(); }
    catch (error) { notify('error', errorText(error)); }
    finally { setSaving(false); }
  }
  return <Modal onClose={onClose}><form onSubmit={submit}>
    <div className="modal-heading"><div><div className="eyebrow">ON-DEMAND SYNC <span className="eyebrow-line" /></div><h2>Sync one work item</h2><p>Queue one Plane task for its project's Notion destination.</p></div><button className="modal-close" aria-label="Close dialog" data-tooltip="Close task sync dialog." type="button" onClick={onClose}>×</button></div>
    <label className="field"><span>Plane task URL</span><input type="url" required value={taskUrl} onChange={(event) => setTaskUrl(event.target.value)} placeholder="https://app.plane.so/workspace/…" /><small>Use a task URL from the Plane instance configured on the service.</small></label>
    <label className="force-option"><input type="checkbox" checked={force} onChange={(event) => setForce(event.target.checked)} /><span><b>Force run on archived config</b><small>Allows a one-time run when the task belongs to an archived config.</small></span></label>
    <div className="modal-footer"><span>Manual requests queue behind an active sync.</span><div><button type="button" data-tooltip="Close without queueing a sync." className="button button-secondary" onClick={onClose}>Cancel</button><button type="submit" data-tooltip="Queue this Plane task URL for sync." className="button button-primary" disabled={saving || !taskUrl.trim()}>{saving ? 'Queueing…' : 'Queue sync'} <span>→</span></button></div></div>
  </form></Modal>;
}

function Modal({ children, onClose, wide = false, stickyFooter = false }: { children: ReactNode; onClose: () => void; wide?: boolean; stickyFooter?: boolean }) {
  useEffect(() => {
    function onKey(event: KeyboardEvent) { if (event.key === 'Escape') onClose(); }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><div className={`modal-card ${wide ? 'wide' : ''} ${stickyFooter ? 'sticky-footer' : ''}`}>{children}</div></div>;
}

function scheduleLabel(config: SyncConfig) {
  if (config.schedule_type === 'INTERVAL') return `Every ${config.interval_value} ${config.interval_unit?.toLowerCase() || 'minutes'}`;
  const days = config.calendar_days_of_week?.map((day) => day.slice(0, 3)).join(', ');
  let repeatLabel = `Every ${config.calendar_interval} day${config.calendar_interval === 1 ? '' : 's'}`;
  if (config.calendar_frequency === 'WEEKLY') {
    repeatLabel = `Every ${config.calendar_interval} week${config.calendar_interval === 1 ? '' : 's'}`;
    if (days) repeatLabel += ` · ${days}`;
  }
  const time = config.calendar_time_of_day?.slice(0, 5) || '09:00';
  return `${repeatLabel} · ${time} UTC`;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short' }).format(date);
}

function formatExactDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'long' }).format(date);
}

function formatRelative(value: string) {
  const date = new Date(value);
  const diff = Date.now() - date.getTime();
  if (!Number.isFinite(diff)) return '—';
  if (diff < 60_000) return 'Just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

function toUtcInput(value?: string | null) {
  return value ? new Date(value).toISOString().slice(0, 16) : '';
}

function fromUtcInput(value: string) {
  return value ? new Date(`${value}:00Z`).toISOString() : null;
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.';
}

export default App;
