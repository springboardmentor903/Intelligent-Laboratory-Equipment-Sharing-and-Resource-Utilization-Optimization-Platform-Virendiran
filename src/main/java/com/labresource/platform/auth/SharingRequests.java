package com.labresource.platform.auth;

import java.math.BigDecimal;

public final class SharingRequests {
    private SharingRequests() { }

    public static class Listing {
        private BigDecimal feePerHour;
        private BigDecimal externalSharePercentage;
        private Integer minimumNoticeHours;
        private Integer maximumBookingHours;
        private String sharingTerms;
        private Boolean active;
        public Listing() { }
        public BigDecimal getFeePerHour(){return feePerHour;} public void setFeePerHour(BigDecimal v){feePerHour=v;}
        public BigDecimal getExternalSharePercentage(){return externalSharePercentage;} public void setExternalSharePercentage(BigDecimal v){externalSharePercentage=v;}
        public Integer getMinimumNoticeHours(){return minimumNoticeHours;} public void setMinimumNoticeHours(Integer v){minimumNoticeHours=v;}
        public Integer getMaximumBookingHours(){return maximumBookingHours;} public void setMaximumBookingHours(Integer v){maximumBookingHours=v;}
        public String getSharingTerms(){return sharingTerms;} public void setSharingTerms(String v){sharingTerms=v;}
        public Boolean getActive(){return active;} public void setActive(Boolean v){active=v;}
    }

    public static class AccessRequest {
        private String purpose;
        private String startDate;
        private String endDate;
        public AccessRequest() { }
        public String getPurpose(){return purpose;} public void setPurpose(String v){purpose=v;}
        public String getStartDate(){return startDate;} public void setStartDate(String v){startDate=v;}
        public String getEndDate(){return endDate;} public void setEndDate(String v){endDate=v;}
    }

    public static class Review {
        private String remarks;
        private String allowedStart;
        private String allowedEnd;
        public Review() { }
        public String getRemarks(){return remarks;} public void setRemarks(String v){remarks=v;}
        public String getAllowedStart(){return allowedStart;} public void setAllowedStart(String v){allowedStart=v;}
        public String getAllowedEnd(){return allowedEnd;} public void setAllowedEnd(String v){allowedEnd=v;}
    }

    public static class Revoke {
        private String reason;
        public Revoke() { }
        public String getReason(){return reason;} public void setReason(String v){reason=v;}
    }
}
