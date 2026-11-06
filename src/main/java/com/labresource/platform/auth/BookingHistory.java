package com.labresource.platform.auth;

import java.time.OffsetDateTime;

public record BookingHistory(long id, String previousStatus, String newStatus, String changedBy,
                             OffsetDateTime changedAt, String remarks) { }
