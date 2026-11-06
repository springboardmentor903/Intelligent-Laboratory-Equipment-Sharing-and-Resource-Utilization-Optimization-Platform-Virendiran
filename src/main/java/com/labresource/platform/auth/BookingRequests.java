package com.labresource.platform.auth;

public final class BookingRequests {
    private BookingRequests() { }
    public record Create(Long equipmentId, String startTime, String endTime, String purpose) { }
    public record Reason(String reason) { }
}
