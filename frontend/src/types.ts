export type ConfigStatus = 'ACTIVE' | 'DISABLED' | 'ARCHIVED';
export type ScheduleType = 'INTERVAL' | 'CALENDAR';
export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED_OVERLAP';
export type TriggerType = 'MANUAL' | 'SCHEDULED';
export type SyncScope = 'PROJECT' | 'WORK_ITEM';
export type SyncOptions = { work_items: boolean; pages: boolean };

export interface Project {
  plane_project_ref: string;
  workspace_slug: string;
  project_id: string;
  name: string;
  identifier: string;
  project_url: string;
  is_archived: boolean;
  is_available: boolean;
  last_refreshed_at: string;
}

export interface SyncConfig {
  config_id: string;
  status: ConfigStatus;
  project: Project;
  notion_project_page_id: string;
  notion_database_id: string;
  notion_data_source_id: string;
  sync_options: SyncOptions;
  schedule_type: ScheduleType;
  interval_value: number | null;
  interval_unit: 'MINUTES' | 'HOURS' | null;
  calendar_frequency: 'DAILY' | 'WEEKLY' | null;
  calendar_interval: number | null;
  calendar_days_of_week: string[];
  calendar_time_of_day: string | null;
  start_at: string | null;
  end_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface SyncJob {
  job_id: string;
  trigger_type: TriggerType;
  sync_scope: SyncScope;
  status: JobStatus;
  config_id: string | null;
  plane_project_ref: string | null;
  requested_task_url: string | null;
  work_item_id: string | null;
  scheduled_for: string | null;
  started_at: string | null;
  finished_at: string | null;
  attempt_count: number;
  fetched_count: number;
  eligible_count: number;
  created_count: number;
  updated_count: number;
  unchanged_count: number;
  failed_count: number;
  error_code: string | null;
  error_message: string | null;
  created_at: string;
}

export interface SyncJobPage {
  items: SyncJob[];
  next_before_id: string | null;
}

export interface SchedulePayload {
  schedule_type: ScheduleType;
  interval_value: number | null;
  interval_unit: 'MINUTES' | 'HOURS' | null;
  calendar_frequency: 'DAILY' | 'WEEKLY' | null;
  calendar_interval: number | null;
  calendar_days_of_week: string[];
  calendar_time_of_day: string | null;
  start_at: string | null;
  end_at: string | null;
}

export interface ConfigPayload extends SchedulePayload {
  status: ConfigStatus;
  sync_options: SyncOptions;
}
