package com.labresource.platform.auth;

import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class EquipmentUsageService {
    private static final String ACTIVE_LOG = "SELECT ul.id,ul.booking_id,ul.instrument_id,i.name,ul.user_id,u.name,ul.usage_type,ul.actual_start_time,ul.actual_end_time,FLOOR(EXTRACT(EPOCH FROM (COALESCE(ul.actual_end_time,CURRENT_TIMESTAMP)-ul.actual_start_time)))::bigint,ul.remarks FROM usage_logs ul JOIN instruments i ON i.id=ul.instrument_id JOIN users u ON u.id=ul.user_id WHERE ul.booking_id=?";
    private final PostgresDatabase database;
    private final EquipmentStatusBroadcaster broadcaster;

    public EquipmentUsageService(PostgresDatabase database, EquipmentStatusBroadcaster broadcaster) {
        this.database = database;
        this.broadcaster = broadcaster;
    }

    public UsageLog checkIn(long bookingId, User user) {
        UsageLog result;
        long equipmentId;
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                BookingRow booking = lockBooking(c, bookingId);
                requireOwner(booking, user);
                if (!"CONFIRMED".equals(booking.status)) throw bad("Only confirmed bookings can be checked in.");
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                if (now.isBefore(booking.start.minusMinutes(15))) throw bad("Check-in opens 15 minutes before the booking starts.");
                if (now.isAfter(booking.end)) throw bad("The booking has ended; check-in is no longer allowed.");

                String equipmentStatus;
                try (PreparedStatement p=c.prepareStatement("SELECT status FROM instruments WHERE id=? FOR UPDATE")) {
                    p.setLong(1,booking.equipmentId);
                    try(ResultSet rs=p.executeQuery()) { if(!rs.next()) throw missing("Equipment not found."); equipmentStatus=rs.getString(1); }
                }
                if ("BOOKED".equalsIgnoreCase(equipmentStatus)) {
                    try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM usage_logs WHERE instrument_id=? AND actual_end_time IS NULL LIMIT 1 FOR UPDATE")) {
                        p.setLong(1,booking.equipmentId);try(ResultSet rs=p.executeQuery()){if(rs.next())throw conflict("This equipment is already checked out to another booking.");}
                    }
                } else if (!"AVAILABLE".equalsIgnoreCase(equipmentStatus)) {
                    throw conflict("This equipment is not available for check-in.");
                }

                try(PreparedStatement p=c.prepareStatement("UPDATE instruments SET status='BOOKED' WHERE id=? AND UPPER(status)='AVAILABLE'")) {p.setLong(1,booking.equipmentId);p.executeUpdate();}
                try(PreparedStatement p=c.prepareStatement("INSERT INTO usage_logs(instrument_id,user_id,booking_id,actual_start_time,usage_type) VALUES(?,?,?,?,?)")) {
                    p.setLong(1,booking.equipmentId);p.setLong(2,user.id());p.setLong(3,bookingId);p.setObject(4,now);p.setString(5,booking.agreementId==null?"BOOKING":"SHARED");p.executeUpdate();
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE bookings SET status='IN_USE',checked_in_at=? WHERE id=? AND status='CONFIRMED'")) {p.setObject(1,now);p.setLong(2,bookingId);if(p.executeUpdate()!=1)throw conflict("The booking changed before check-in completed.");}
                writeHistory(c,bookingId,"CONFIRMED","IN_USE",user.id(),user.name(),"Checked in.");
                result=findOpenUsage(c,bookingId);equipmentId=booking.equipmentId;c.commit();
            } catch(RuntimeException|SQLException ex) { c.rollback();throw ex; }
        } catch(SQLException ex) { throw new IllegalStateException("Could not check in to this booking.",ex); }
        broadcaster.publish(equipmentId);
        return result;
    }

    public UsageLog checkOut(long bookingId, User user, String remarks) {
        UsageLog result;
        long equipmentId;
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                BookingRow booking=lockBooking(c,bookingId);requireOwner(booking,user);
                if(!"IN_USE".equals(booking.status))throw bad("Only an in-use booking can be checked out.");
                long logId;
                try(PreparedStatement p=c.prepareStatement("SELECT id FROM usage_logs WHERE booking_id=? AND actual_end_time IS NULL FOR UPDATE")) {
                    p.setLong(1,bookingId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw conflict("The active usage record is missing.");logId=rs.getLong(1);}
                }
                OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
                String cleanRemarks=remarks==null?null:remarks.trim();
                try(PreparedStatement p=c.prepareStatement("UPDATE usage_logs SET actual_end_time=?,remarks=COALESCE(NULLIF(?,''),remarks) WHERE id=? AND actual_end_time IS NULL")) {
                    p.setObject(1,now);p.setString(2,cleanRemarks);p.setLong(3,logId);if(p.executeUpdate()!=1)throw conflict("The usage record was already closed.");
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE bookings SET status='COMPLETED',checked_out_at=? WHERE id=? AND status='IN_USE'")) {p.setObject(1,now);p.setLong(2,bookingId);if(p.executeUpdate()!=1)throw conflict("The booking changed before check-out completed.");}
                try(PreparedStatement p=c.prepareStatement("UPDATE instruments SET status='AVAILABLE' WHERE id=? AND UPPER(status)='BOOKED'")) {p.setLong(1,booking.equipmentId);p.executeUpdate();}
                writeHistory(c,bookingId,"IN_USE","COMPLETED",user.id(),user.name(),cleanRemarks==null||cleanRemarks.isBlank()?"Checked out.":cleanRemarks);
                result=findUsage(c,bookingId);equipmentId=booking.equipmentId;c.commit();
            }catch(RuntimeException|SQLException ex){c.rollback();throw ex;}
        }catch(SQLException ex){throw new IllegalStateException("Could not check out of this booking.",ex);}
        broadcaster.publish(equipmentId);
        return result;
    }

    public Map<String,Object> statusPage(String status,int page,int size,boolean includeUser) {
        int safePage=Math.max(0,page),safeSize=Math.min(100,Math.max(1,size));
        String normalized=normalizeFilter(status);
        String where=normalized==null?"":" WHERE UPPER(i.status)=?";
        String sql="SELECT i.id,i.name,i.asset_tag,i.status,ul.booking_id,ul.actual_start_time,b.end_time,"+
            "CASE WHEN ul.id IS NULL THEN NULL ELSE FLOOR(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-ul.actual_start_time)))::bigint END,"+
            (includeUser?"u.name,u.email":"NULL::varchar,NULL::varchar")+",n.start_time,n.end_time " +
            "FROM instruments i LEFT JOIN usage_logs ul ON ul.instrument_id=i.id AND ul.actual_end_time IS NULL " +
            "LEFT JOIN bookings b ON b.id=ul.booking_id LEFT JOIN users u ON u.id=ul.user_id " +
            "LEFT JOIN LATERAL (SELECT nb.start_time,nb.end_time FROM bookings nb WHERE nb.instrument_id=i.id AND nb.status='CONFIRMED' AND nb.start_time>CURRENT_TIMESTAMP ORDER BY nb.start_time LIMIT 1) n ON TRUE"+
            where+" ORDER BY i.name LIMIT ? OFFSET ?";
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement(sql)){
            int at=1;if(normalized!=null)p.setString(at++,normalized);p.setInt(at++,safeSize);p.setLong(at,(long)safePage*safeSize);
            List<EquipmentLiveStatus> items=new ArrayList<>();try(ResultSet rs=p.executeQuery()){while(rs.next()){
                long bookingId=rs.getLong(5);Long nullableBooking=rs.wasNull()?null:bookingId;
                OffsetDateTime usageStart=rs.getObject(6,OffsetDateTime.class),scheduledEnd=rs.getObject(7,OffsetDateTime.class);
                long elapsed=rs.getLong(8);Long nullableElapsed=rs.wasNull()?null:elapsed;
                items.add(new EquipmentLiveStatus(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),usageStart!=null,nullableBooking,usageStart,scheduledEnd,nullableElapsed,rs.getString(9),rs.getString(10),rs.getObject(11,OffsetDateTime.class),rs.getObject(12,OffsetDateTime.class)));
            }}
            String countSql="SELECT COUNT(*) FROM instruments i"+where;long total;try(PreparedStatement count=c.prepareStatement(countSql)){if(normalized!=null)count.setString(1,normalized);try(ResultSet rs=count.executeQuery()){rs.next();total=rs.getLong(1);}}
            return Map.of("items",items,"page",safePage,"size",safeSize,"totalItems",total,"totalPages",(int)Math.ceil((double)total/safeSize));
        }catch(SQLException ex){throw new IllegalStateException("Could not load live equipment status.",ex);}
    }

    private BookingRow lockBooking(Connection c,long id)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT b.id,b.instrument_id,b.user_id,b.status,b.start_time,b.end_time,b.agreement_id FROM bookings b WHERE b.id=? FOR UPDATE")){
            p.setLong(1,id);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Booking not found.");long agreement=rs.getLong(7);Long agreementId=rs.wasNull()?null:agreement;return new BookingRow(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getObject(5,OffsetDateTime.class),rs.getObject(6,OffsetDateTime.class),agreementId);}
        }
    }
    private void requireOwner(BookingRow booking,User user){if(booking.userId!=user.id())throw missing("Booking not found.");}
    private UsageLog findOpenUsage(Connection c,long bookingId)throws SQLException{return findUsage(c,bookingId,true);}
    private UsageLog findUsage(Connection c,long bookingId)throws SQLException{return findUsage(c,bookingId,false);}
    private UsageLog findUsage(Connection c,long bookingId,boolean open)throws SQLException{
        String sql=ACTIVE_LOG+(open?" AND ul.actual_end_time IS NULL":" ORDER BY ul.actual_start_time DESC LIMIT 1");
        try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,bookingId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Usage record not found.");return usage(rs);}}
    }
    static UsageLog usage(ResultSet rs)throws SQLException{return new UsageLog(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getObject(8,OffsetDateTime.class),rs.getObject(9,OffsetDateTime.class),rs.getLong(10),rs.getString(11));}
    static void writeHistory(Connection c,long booking,String old,String next,Long userId,String name,String remarks)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("INSERT INTO booking_history(booking_id,previous_status,new_status,changed_by,changed_by_name,remarks) VALUES(?,?,?,?,?,?)")){
            p.setLong(1,booking);p.setString(2,old);p.setString(3,next);if(userId==null)p.setNull(4,Types.BIGINT);else p.setLong(4,userId);p.setString(5,name);p.setString(6,remarks);p.executeUpdate();
        }
    }
    static BookingService.BookingException bad(String message){return new BookingService.BookingException(400,message);}
    static BookingService.BookingException conflict(String message){return new BookingService.BookingException(409,message);}
    static BookingService.BookingException missing(String message){return new BookingService.BookingException(404,message);}
    static String normalizeFilter(String status){if(status==null||status.isBlank())return null;String normalized=status.trim().toUpperCase(Locale.ROOT).replace(' ','_');if(!Set.of("AVAILABLE","BOOKED","UNDER_MAINTENANCE","OUT_OF_SERVICE","RETIRED").contains(normalized))throw bad("Unknown equipment status filter.");return normalized;}
    private record BookingRow(long id,long equipmentId,long userId,String status,OffsetDateTime start,OffsetDateTime end,Long agreementId){}
}
