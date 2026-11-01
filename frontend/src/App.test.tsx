import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import { api } from './api';
import type { SyncConfig, SyncJob, SyncJobPage } from './types';

vi.mock('./api', () => ({
  api: {
    health: vi.fn(),
    listConfigs: vi.fn(),
    listJobs: vi.fn(),
    listConfigJobs: vi.fn(),
    nextScheduledRuns: vi.fn(),
    enqueueProjectSync: vi.fn(),
    enqueueTaskSync: vi.fn(),
  },
}));

const config: SyncConfig = {
  config_id: 'config-1',
  status: 'ARCHIVED',
  project: {
    plane_project_ref: 'project-ref-1',
    workspace_slug: 'workspace',
    project_id: 'project-1',
    name: 'Demo Project',
    identifier: 'DEMO',
    project_url: 'https://app.plane.so/workspace/projects/project-1',
    is_archived: false,
    is_available: true,
    last_refreshed_at: '2026-11-01T10:00:00Z',
  },
  notion_project_page_id: 'page-1',
  notion_database_id: 'database-1',
  notion_data_source_id: 'data-source-1',
  sync_options: { work_items: true, pages: false },
  schedule_type: 'INTERVAL',
  interval_value: 30,
  interval_unit: 'MINUTES',
  calendar_frequency: null,
  calendar_interval: null,
  calendar_days_of_week: [],
  calendar_time_of_day: null,
  start_at: null,
  end_at: null,
  created_at: '2026-10-31T10:00:00Z',
  updated_at: '2026-11-01T10:00:00Z',
};

describe('manual sync flows', () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.health).mockResolvedValue({ status: 'UP' });
    vi.mocked(api.listConfigs).mockResolvedValue([config]);
    vi.mocked(api.listJobs).mockResolvedValue({ items: [], next_before_id: null });
    vi.mocked(api.listConfigJobs).mockResolvedValue({ items: [], next_before_id: null });
    vi.mocked(api.nextScheduledRuns).mockResolvedValue({});
    vi.mocked(api.enqueueProjectSync).mockResolvedValue({ job_id: 'job-1', status: 'PENDING' });
    vi.mocked(api.enqueueTaskSync).mockResolvedValue({ job_id: 'job-2', status: 'PENDING' });
  });

  it('lets the user force a one-time project run for an archived config', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: /configurations/i }));
    await user.click(screen.getByTitle('Queue a project sync'));
    await user.click(screen.getByRole('checkbox', { name: /force run on archived config/i }));
    await user.click(screen.getByRole('button', { name: /queue sync/i }));

    expect(api.enqueueProjectSync).toHaveBeenCalledWith('config-1', true);
  });

  it('shows the force checkbox on the task URL form and sends the selected value', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: /sync a task/i }));
    await user.type(screen.getByLabelText(/Plane task URL/), 'https://app.plane.so/workspace/browse/DEMO-9');
    await user.click(screen.getByRole('checkbox', { name: /force run on archived config/i }));
    await user.click(screen.getByRole('button', { name: /queue sync/i }));

    expect(api.enqueueTaskSync).toHaveBeenCalledWith('https://app.plane.so/workspace/browse/DEMO-9', true);
  });

  it('loads subsequent run-history pages using the last Mongo id', async () => {
    vi.mocked(api.listJobs).mockImplementation(async (_filters, beforeId) => beforeId
      ? { items: [{
        job_id: 'job-2', trigger_type: 'MANUAL', sync_scope: 'WORK_ITEM', status: 'SUCCESS',
        config_id: 'config-1', plane_project_ref: 'project-ref-1', requested_task_url: null,
        work_item_id: 'item-2', scheduled_for: null, started_at: '2026-11-01T10:00:00Z',
        finished_at: '2026-11-01T10:00:01Z', attempt_count: 1, fetched_count: 1, eligible_count: 1,
        created_count: 1, updated_count: 0, unchanged_count: 0, failed_count: 0,
        error_code: null, error_message: null, created_at: '2026-11-01T10:00:00Z',
      }], next_before_id: null }
      : { items: [], next_before_id: 'job-1' });
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getAllByRole('button', { name: /run history/i })[0]);
    await user.click(await screen.findByRole('button', { name: /load more runs/i }));

    expect(api.listJobs).toHaveBeenCalledWith({}, 'job-1');
  });

  it('loads subsequent config run pages using the last Mongo id', async () => {
    vi.mocked(api.listConfigJobs).mockImplementation(async (_configId, beforeId) => beforeId
      ? { items: [], next_before_id: null }
      : { items: [], next_before_id: 'config-job-1' });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: /configurations/i }));
    await user.click(screen.getByRole('button', { name: /demo project/i }));
    await user.click(await screen.findByRole('button', { name: /load more runs/i }));

    expect(api.listConfigJobs).toHaveBeenCalledWith('config-1', 'config-job-1');
  });

  it('refreshes run history after a task URL is queued', async () => {
    const queuedJob: SyncJob = {
      job_id: 'queued-1', trigger_type: 'MANUAL', sync_scope: 'WORK_ITEM', status: 'PENDING',
      config_id: 'config-1', plane_project_ref: 'project-ref-1', requested_task_url: 'https://app.plane.so/workspace/browse/DEMO-9',
      work_item_id: 'item-9', scheduled_for: null, started_at: null, finished_at: null,
      attempt_count: 0, fetched_count: 0, eligible_count: 0, created_count: 0, updated_count: 0,
      unchanged_count: 0, failed_count: 0, error_code: null, error_message: null, created_at: '2026-11-01T10:00:00Z',
    };
    let callCount = 0;
    vi.mocked(api.listJobs).mockImplementation(async () => {
      callCount++;
      return callCount >= 4
        ? { items: [queuedJob], next_before_id: null }
        : { items: [], next_before_id: null };
    });
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getAllByRole('button', { name: /run history/i })[0]);
    await waitFor(() => expect(api.listJobs).toHaveBeenCalledTimes(2));
    await user.click(screen.getByRole('button', { name: /sync a task/i }));
    await user.type(screen.getByLabelText(/Plane task URL/), 'https://app.plane.so/workspace/browse/DEMO-9');
    await user.click(screen.getByRole('button', { name: /queue sync/i }));

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2));
    expect(api.listJobs).toHaveBeenCalledTimes(4);
  });

  it('keeps the selected run filter when an older request finishes later', async () => {
    const staleJob: SyncJob = {
      job_id: 'stale-job', trigger_type: 'MANUAL', sync_scope: 'WORK_ITEM', status: 'SUCCESS',
      config_id: 'config-1', plane_project_ref: 'project-ref-1', requested_task_url: null,
      work_item_id: 'item-1', scheduled_for: null, started_at: '2026-11-01T10:00:00Z',
      finished_at: '2026-11-01T10:00:01Z', attempt_count: 1, fetched_count: 1, eligible_count: 1,
      created_count: 1, updated_count: 0, unchanged_count: 0, failed_count: 0,
      error_code: null, error_message: null, created_at: '2026-11-01T10:00:00Z',
    };
    const filteredJobs: SyncJob[] = [
      { ...staleJob, job_id: 'failed-job-1', status: 'FAILED' },
      { ...staleJob, job_id: 'failed-job-2', status: 'FAILED' },
    ];
    let resolveUnfiltered: (page: SyncJobPage) => void = () => {};
    vi.mocked(api.listJobs).mockImplementation((filters) => {
      if (filters?.status === 'FAILED') return Promise.resolve({ items: filteredJobs, next_before_id: null });
      return new Promise((resolve) => { resolveUnfiltered = resolve; });
    });
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getAllByRole('button', { name: /run history/i })[0]);
    await user.selectOptions(await screen.findByLabelText('Filter by status'), 'FAILED');
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    resolveUnfiltered({ items: [staleJob], next_before_id: null });

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(api.listJobs).toHaveBeenCalledWith({ status: 'FAILED' });
  });
});
