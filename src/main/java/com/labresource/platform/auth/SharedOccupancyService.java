package com.labresource.platform.auth;

import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class SharedOccupancyService {
    public static final ZoneId PLATFORM_ZONE=ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN=LocalTime.of(8,0),CLOSE=LocalTime.of(20,0);
    private final PostgresDatabase database;
    public SharedOccupancyService(PostgresDatabase database){this.database=database;}

    public List<Map<String,Object>> occupancy(long equipmentId,OffsetDateTime from,OffsetDateTime to,User viewer,boolean systemAdmin){
        try(Connection c=database.open()){
            long owner;try(PreparedStatement p=c.prepareStatement("SELECT owner_institution_id FROM instruments WHERE id=?")){p.setLong(1,equipmentId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw new BookingService.BookingException(404,"Equipment not found.");owner=rs.getLong(1);if(rs.wasNull())owner=-1;}}
            boolean canSeePartner=systemAdmin||(viewer!=null&&owner>0&&Objects.equals(viewer.institutionId(),owner)&&Set.of("LAB_MANAGER","INSTITUTION_ADMIN").contains(viewer.role()));
            String sql="SELECT b.id,b.start_time,b.end_time,b.status,(b.agreement_id IS NOT NULL) external,pi.name FROM bookings b LEFT JOIN sharing_agreements a ON a.id=b.agreement_id LEFT JOIN institutions pi ON pi.id=a.partner_institution_id WHERE b.instrument_id=? AND b.status IN ('PENDING_APPROVAL','CONFIRMED','IN_USE') AND b.start_time<? AND b.end_time>? ORDER BY b.start_time";
            List<Map<String,Object>> slots=new ArrayList<>();try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,equipmentId);p.setObject(2,to);p.setObject(3,from);try(ResultSet rs=p.executeQuery()){while(rs.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("bookingId",rs.getLong(1));m.put("startTime",rs.getObject(2,OffsetDateTime.class));m.put("endTime",rs.getObject(3,OffsetDateTime.class));m.put("status",rs.getString(4));m.put("type",rs.getBoolean(5)?"EXTERNAL":"INTERNAL");if(canSeePartner&&rs.getBoolean(5))m.put("partnerInstitution",rs.getString(6));slots.add(m);}}}
            Map<String,Object> response=new LinkedHashMap<>();response.put("slots",slots);response.put("allowedWindows",allowedWindows(c,equipmentId,viewer));response.put("weeklyShareLimitMinutes",shareLimit(c,equipmentId,from.toInstant().atZone(PLATFORM_ZONE).toLocalDate()));response.put("externalMinutesUsed",externalMinutes(c,equipmentId,weekStart(from.toInstant().atZone(PLATFORM_ZONE).toLocalDate())));return response;
        }catch(SQLException e){throw new IllegalStateException("Could not load shared schedule.",e);}
    }

    public long shareLimit(Connection c,long equipmentId,LocalDate date)throws SQLException{
        LocalDate week=weekStart(date);long capacity=operatingCapacityMinutes(week);
        try(PreparedStatement p=c.prepareStatement("SELECT external_share_percentage FROM sharing_listings WHERE equipment_id=?")){p.setLong(1,equipmentId);try(ResultSet rs=p.executeQuery()){if(!rs.next())return 0;return java.math.BigDecimal.valueOf(capacity).multiply(rs.getBigDecimal(1)).divide(java.math.BigDecimal.valueOf(100),0,java.math.RoundingMode.DOWN).longValue();}}
    }

    public long externalMinutes(Connection c,long equipmentId,LocalDate weekStart)throws SQLException{
        LocalDateTime localStart=weekStart.atStartOfDay(),localEnd=weekStart.plusDays(7).atStartOfDay();OffsetDateTime start=localStart.atZone(PLATFORM_ZONE).toOffsetDateTime(),end=localEnd.atZone(PLATFORM_ZONE).toOffsetDateTime();long used=0;
        String sql="SELECT start_time,end_time FROM bookings WHERE instrument_id=? AND agreement_id IS NOT NULL AND status IN ('PENDING_APPROVAL','CONFIRMED','IN_USE','COMPLETED') AND start_time<? AND end_time>?";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,equipmentId);p.setObject(2,end);p.setObject(3,start);try(ResultSet rs=p.executeQuery()){while(rs.next()){OffsetDateTime bStart=rs.getObject(1,OffsetDateTime.class),bEnd=rs.getObject(2,OffsetDateTime.class);used+=operatingMinutes(bStart.isAfter(start)?bStart:start,bEnd.isBefore(end)?bEnd:end);}}}return used;
    }

    public long operatingMinutes(OffsetDateTime start,OffsetDateTime end){
        if(start==null||end==null||!end.isAfter(start))return 0;ZonedDateTime localStart=start.atZoneSameInstant(PLATFORM_ZONE),localEnd=end.atZoneSameInstant(PLATFORM_ZONE);long minutes=0;
        for(LocalDate date=localStart.toLocalDate();!date.isAfter(localEnd.toLocalDate());date=date.plusDays(1)){
            Instant open=date.atTime(OPEN).atZone(PLATFORM_ZONE).toInstant(),close=date.atTime(CLOSE).atZone(PLATFORM_ZONE).toInstant();Instant left=start.toInstant().isAfter(open)?start.toInstant():open;Instant right=end.toInstant().isBefore(close)?end.toInstant():close;if(right.isAfter(left))minutes+=Duration.between(left,right).toMinutes();
        }return minutes;
    }

    public long operatingCapacityMinutes(LocalDate weekStart){
        OffsetDateTime start=weekStart.atStartOfDay(PLATFORM_ZONE).toOffsetDateTime();OffsetDateTime end=weekStart.plusWeeks(1).atStartOfDay(PLATFORM_ZONE).toOffsetDateTime();return operatingMinutes(start,end);
    }

    public static LocalDate weekStart(LocalDate date){return date.with(java.time.DayOfWeek.MONDAY);}

    private List<Map<String,Object>> allowedWindows(Connection c,long equipmentId,User viewer)throws SQLException{
        if(viewer==null)return List.of();String sql="SELECT a.id,a.start_date,a.end_date,a.allowed_start,a.allowed_end,a.fee_per_hour,l.minimum_notice_hours,l.maximum_booking_hours FROM sharing_agreements a JOIN access_grants g ON g.agreement_id=a.id JOIN sharing_listings l ON l.equipment_id=a.equipment_id WHERE a.equipment_id=? AND g.user_id=? AND g.revoked_at IS NULL AND a.status='ACTIVE' AND a.end_date>=CURRENT_DATE ORDER BY a.end_date";
        List<Map<String,Object>> result=new ArrayList<>();try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,equipmentId);p.setLong(2,viewer.id());try(ResultSet rs=p.executeQuery()){while(rs.next()){Map<String,Object> item=new LinkedHashMap<>();item.put("agreementId",rs.getLong(1));item.put("startDate",rs.getObject(2,LocalDate.class));item.put("endDate",rs.getObject(3,LocalDate.class));item.put("allowedStart",rs.getObject(4,LocalTime.class));item.put("allowedEnd",rs.getObject(5,LocalTime.class));item.put("feePerHour",rs.getBigDecimal(6));item.put("minimumNoticeHours",rs.getInt(7));item.put("maximumBookingHours",rs.getInt(8));result.add(item);}}}return result;
    }
}
