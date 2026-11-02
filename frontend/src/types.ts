export type ConfigStatus = 'ACTIVE' | 'DISABLED' | 'ARCHIVED';
export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED_OVERLAP';
export type JobType = 'MANUAL' | 'SCHEDULED';
export type SyncScope = 'PROJECT' | 'WORK_ITEM';
export type SyncOptions = { work_items: boolean; pages: boolean };

export interface Project {
  plane_project_document_id: string;
  workspace_slug: string;
  project_id: string;
  name: string;
  identifier: string;
  project_url: string;
  is_archived: boolean;
  is_available: boolean;
}

export interface SyncConfig {
  config_id: string;
  status: ConfigStatus;
  project: Project;
  notion_project_page_id: string;
  notion_database_id: string;
  notion_data_source_id: string;
  sync_options: SyncOptions;
  schedule: SyncSchedule;
  created_at: string;
  updated_at: string;
}

export interface SyncJob {
  job_id: string;
  job_type: JobType;
  scope: SyncScope;
  job_status: JobStatus;
  config_id: string | null;
  plane_project_document_id: string | null;
  plane_work_item: { task_url: string; work_item_id: string } | null;
  scheduled_for: string | null;
  started_at: string | null;
  finished_at: string | null;
  attempt_count: number;
  result: {
    eligible_plane_work_items_count: number;
    created_notion_pages_count: number;
    updated_notion_pages_count: number;
    unchanged_notion_pages_count: number;
  };
  error_code: string | null;
  error_message: string | null;
  created_at: string;
}

export interface SyncJobPage {
  items: SyncJob[];
  next_before_id: string | null;
}

export interface IntervalSchedule {
  value: number;
  unit: 'MINUTES' | 'HOURS';
}

export interface CalendarSchedule {
  frequency: 'DAILY' | 'WEEKLY';
  interval: number;
  days_of_week: string[];
  time_of_day: string;
}

export type SyncSchedule = {
  type: 'INTERVAL';
  start_at: string | null;
  end_at: string | null;
  interval: IntervalSchedule;
  calendar: null;
} | {
  type: 'CALENDAR';
  start_at: string | null;
  end_at: string | null;
  interval: null;
  calendar: CalendarSchedule;
};

export interface ConfigPayload {
  status: ConfigStatus;
  sync_options: SyncOptions;
  schedule: SyncSchedule;
}
