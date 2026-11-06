package com.labresource.platform.auth;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record SharingRequestView(long id,long equipmentId,String equipmentName,String assetTag,String category,
    long requesterId,String requesterName,String requesterEmail,long requesterInstitutionId,String requesterInstitution,
    String purpose,LocalDate startDate,LocalDate endDate,String status,Long requesterReviewedBy,
    String requesterReviewerName,OffsetDateTime requesterReviewedAt,String requesterReviewRemarks,
    Long reviewedBy,String reviewerName,OffsetDateTime reviewedAt,String reviewRemarks,Long agreementId,
    OffsetDateTime createdAt) { }
