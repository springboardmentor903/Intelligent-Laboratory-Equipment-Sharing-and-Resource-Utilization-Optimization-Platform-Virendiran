package com.labresource.platform.auth;

import java.time.OffsetDateTime;
import java.math.BigDecimal;

public record Booking(long id, long equipmentId, String equipmentName, long userId, String userName,
                      String userEmail, OffsetDateTime startTime, OffsetDateTime endTime,
                      String purpose, String status, Long approvedBy, String approvedByName, OffsetDateTime approvedAt,
                      String rejectionReason, OffsetDateTime cancelledAt, String cancellationReason, Long agreementId,
                      BigDecimal feePerHour, BigDecimal estimatedFee) { }
