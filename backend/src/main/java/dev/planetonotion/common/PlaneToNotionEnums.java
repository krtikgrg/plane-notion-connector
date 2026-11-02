package dev.planetonotion.common;

public final class PlaneToNotionEnums {

    private PlaneToNotionEnums() {
    }

    public enum ConfigStatus {
        ACTIVE,
        DISABLED,
        ARCHIVED
    }

    public enum ScheduleType {
        INTERVAL,
        CALENDAR
    }

    public enum IntervalUnit {
        MINUTES,
        HOURS
    }

    public enum CalendarFrequency {
        DAILY,
        WEEKLY
    }

    public enum SyncJobStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        SKIPPED_OVERLAP
    }

    public enum SyncJobType {
        MANUAL,
        SCHEDULED
    }

    public enum SyncScope {
        PROJECT,
        WORK_ITEM
    }
}
