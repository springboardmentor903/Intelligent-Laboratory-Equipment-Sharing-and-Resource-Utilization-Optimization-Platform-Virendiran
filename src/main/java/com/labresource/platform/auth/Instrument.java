package com.labresource.platform.auth;

public record Instrument(long id, String assetTag, String name, String category, String location, String status,
                         String description, Long ownerInstitutionId, String ownerInstitution, boolean shareable) { }
