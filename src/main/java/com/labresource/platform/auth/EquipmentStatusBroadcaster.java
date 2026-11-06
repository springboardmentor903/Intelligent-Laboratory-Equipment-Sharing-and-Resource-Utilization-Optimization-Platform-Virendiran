package com.labresource.platform.auth;

public interface EquipmentStatusBroadcaster {
    void publish(long equipmentId);
}
