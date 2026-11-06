package com.labresource.platform.auth;

import java.time.OffsetDateTime;

public record EquipmentLiveStatus(long equipmentId, String equipmentName, String assetTag,
                                  String status, boolean inUse, Long currentBookingId,
                                  OffsetDateTime usageStartTime, OffsetDateTime scheduledBookingEndTime,
                                  Long elapsedUsageSeconds, String currentUserName, String currentUserEmail,
                                  OffsetDateTime nextBookingStartTime, OffsetDateTime nextBookingEndTime) { }
