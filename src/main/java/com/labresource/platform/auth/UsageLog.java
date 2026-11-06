package com.labresource.platform.auth;

import java.time.OffsetDateTime;

public record UsageLog(long id, long bookingId, long equipmentId, String equipmentName,
                       long userId, String userName, String usageType,
                       OffsetDateTime actualStartTime, OffsetDateTime actualEndTime,
                       long durationSeconds, String remarks) { }
