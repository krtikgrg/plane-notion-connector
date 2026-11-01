import type { ConfigPayload, Project, SyncConfig, SyncJobPage } from './types';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, {
    ...init,
    headers,
  });
  if (!response.ok) {
    const fallback = `Request failed (${response.status})`;
    let message = fallback;
    try {
      const body: unknown = await response.json();
      if (body !== null && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
        message = body.message;
      }
    } catch {
      message = fallback;
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
});

export const api = {
  health: () => request<{ status: string }>('/api/health'),
  testPlaneConnection: () => request<{ status: string }>(
    '/api/plane/connection/test', json('POST')),
  listProjects: (workspaceSlug: string) => request<Project[]>(
    `/api/plane/projects?workspace_slug=${encodeURIComponent(workspaceSlug)}`),
  refreshProjects: (workspaceSlug: string) => request<{
    workspace_slug: string; refreshed_at: string; projects: Project[];
  }>('/api/plane/projects/refresh', json('POST', { workspace_slug: workspaceSlug })),
  resolveProject: (projectUrl: string) => request<Project>(
    '/api/plane/projects/resolve', json('POST', { project_url: projectUrl })),
  listConfigs: () => request<SyncConfig[]>('/api/configs'),
  nextScheduledRuns: () => request<Record<string, string>>('/api/sync-jobs/next-scheduled'),
  createConfig: (body: ConfigPayload & { plane_project_ref: string }) => request<SyncConfig>('/api/configs', json('POST', body)),
  updateConfig: (id: string, body: ConfigPayload) => request<SyncConfig>(
    `/api/configs/${id}`, json('PATCH', body)),
  listJobs: (filters: Record<string, string> = {}, beforeId?: string, pageSize = 20) => {
    const query = new URLSearchParams({ ...filters, page_size: pageSize.toString(), ...(beforeId ? { before_id: beforeId } : {}) }).toString();
    return request<SyncJobPage>(`/api/sync-jobs?${query}`);
  },
  listConfigJobs: (configId: string, beforeId?: string, pageSize = 20) => {
    const query = new URLSearchParams({ page_size: pageSize.toString(), ...(beforeId ? { before_id: beforeId } : {}) }).toString();
    return request<SyncJobPage>(`/api/configs/${configId}/sync-jobs?${query}`);
  },
  enqueueProjectSync: (id: string, forceArchived: boolean) => request<{ job_id: string; status: string }>(
    `/api/configs/${id}/sync-jobs`, json('POST', { force_archived_config: forceArchived })),
  enqueueTaskSync: (taskUrl: string, forceArchived: boolean) => request<{ job_id: string; status: string }>(
    '/api/sync-jobs/manual', json('POST', { task_url: taskUrl, force_archived_config: forceArchived })),
};
