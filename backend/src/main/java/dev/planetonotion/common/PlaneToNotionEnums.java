package dev.planetonotion.common;

public final class PlaneToNotionEnums {

    private PlaneToNotionEnums() {
    }

    public enum ConfigStatus {
        ACTIVE, DISABLED, ARCHIVED
    }

    public enum ConfigOperationStatus {
        READY, PROVISIONING, UPDATING, FAILED
    }

    public enum ConfigOperationType {
        CREATE, UPDATE
    }

    public enum ScheduleType {
        INTERVAL, CALENDAR
    }

    public enum IntervalUnit {
        MINUTES, HOURS
    }

    public enum CalendarFrequency {
        DAILY, WEEKLY
    }

    public enum JobStatus {
        PENDING, HELD, RUNNING, SUCCESS, FAILED, SKIPPED_OVERLAP
    }

    public enum JobType {
        MANUAL, SCHEDULED, CONFIGURATION
    }

    public enum SyncScope {
        PROJECT, WORK_ITEM
    }

    public enum IntegrationProvider {
        PLANE, NOTION
    }
}
