package dev.planetonotion.common;

/**
 * Application-owned enums for configuration, scheduling, queued jobs, sync
 * scope, and provider handling.
 */
public final class PlaneToNotionEnums {

    private PlaneToNotionEnums() {
    }

    /** User-facing availability state of a project's sync configuration. */
    public enum ConfigStatus {
        /** Scheduled syncs are enabled. */
        ACTIVE,
        /** Scheduled syncs are paused; manual syncs remain available. */
        DISABLED,
        /** The config is retired; a manual run requires the force-run option. */
        ARCHIVED
    }

    /** Background lifecycle state for creating or updating a configuration. */
    public enum ConfigOperationStatus {
        /** No config operation is pending; the config can be used. */
        READY,
        /** The worker is creating the config's Notion destination and schedule. */
        PROVISIONING,
        /** The worker is applying config changes; new sync requests are held. */
        UPDATING,
        /**
         * The most recent config operation failed; its error is available for repair.
         */
        FAILED
    }

    /** Kind of background operation requested for a configuration. */
    public enum ConfigOperationType {
        /** Create and provision a new config. */
        CREATE,
        /** Apply changes to an existing config. */
        UPDATE
    }

    /** Recurrence model used by a config's schedule. */
    public enum ScheduleType {
        /** Repeat after a fixed elapsed duration. */
        INTERVAL,
        /** Repeat on daily or weekly calendar days at a specified time. */
        CALENDAR
    }

    /** Time unit for a fixed-interval schedule. */
    public enum IntervalUnit {
        /** Interval length is measured in minutes. */
        MINUTES,
        /** Interval length is measured in hours. */
        HOURS
    }

    /** Calendar recurrence frequency for a scheduled sync. */
    public enum CalendarFrequency {
        /** Repeat every configured number of days. */
        DAILY,
        /** Repeat on selected weekdays, every configured number of weeks. */
        WEEKLY
    }

    /** Queue and run-history state of a sync or configuration job. */
    public enum JobStatus {
        /** Queued and waiting for the worker or scheduled due time. */
        PENDING,
        /** Queued sync is paused until its config update finishes. */
        HELD,
        /** Claimed by the worker and currently executing. */
        RUNNING,
        /** Finished successfully. */
        SUCCESS,
        /** Finished unsuccessfully; failure details are recorded on the job. */
        FAILED,
        /**
         * Scheduled occurrence was skipped because another sync for the config was
         * running.
         */
        SKIPPED_OVERLAP
    }

    /** Origin or purpose of a queued job. */
    public enum JobType {
        /** Sync explicitly requested by a user. */
        MANUAL,
        /** Recurring project sync created by the scheduler. */
        SCHEDULED,
        /** Background config creation or update operation. */
        CONFIGURATION
    }

    /** Amount of Plane data a sync job processes. */
    public enum SyncScope {
        /** Sync eligible work items across a project. */
        PROJECT,
        /** Sync one work item identified by a Plane task URL. */
        WORK_ITEM
    }

    /** Lifecycle value written to the Notion work-item schema. */
    public enum WorkItemLifecycle {
        /** Work item is currently eligible and included in sync. */
        ACTIVE,
        /**
         * Included as a Notion option for future reconciliation; the current sync
         * never assigns this value.
         */
        MISSING_FROM_PROJECT
    }

    /** Provider identity used to route API handling and errors. */
    public enum IntegrationProvider {
        /** Plane project-tracking API. */
        PLANE("Plane"),
        /** Notion workspace and content API. */
        NOTION("Notion");

        private final String displayName;

        IntegrationProvider(String displayName) {
            this.displayName = displayName;
        }

        /** Human-readable provider name for messages shown outside the code. */
        public String displayName() {
            return displayName;
        }
    }
}
