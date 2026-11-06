package com.labresource.platform.auth;

import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class BookingService {
    private static final int MAX_DURATION_HOURS = 8;
    private final PostgresDatabase database;
    private final EquipmentStatusBroadcaster broadcaster;
    private final SharingService sharing;
    public BookingService(PostgresDatabase database,EquipmentStatusBroadcaster broadcaster,SharingService sharing) { this.database = database;this.broadcaster=broadcaster;this.sharing=sharing; }

    public Booking create(User user, BookingRequests.Create request) {
        if (request == null || request.equipmentId() == null || request.equipmentId() < 1) throw bad("A valid equipmentId is required.");
        OffsetDateTime start = parse(request.startTime(), "startTime"), end = parse(request.endTime(), "endTime");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!start.isAfter(now)) throw bad("Booking start time must be in the future.");
        if (!end.isAfter(start)) throw bad("Booking end time must be after start time.");
        if (Duration.between(start, end).compareTo(Duration.ofHours(MAX_DURATION_HOURS)) > 0) throw bad("Booking duration cannot exceed 8 hours.");
        String purpose = request.purpose() == null ? "" : request.purpose().trim();
        if (purpose.isEmpty() || purpose.length() > 1000) throw bad("Purpose is required and must be at most 1000 characters.");
        Booking created;
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                // Lock the equipment row so all booking writers for this equipment serialize before conflict check.
                String equipmentStatus;
                try (PreparedStatement p = c.prepareStatement("SELECT status FROM instruments WHERE id=? FOR UPDATE")) {
                    p.setLong(1, request.equipmentId());
                    try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw missing("Equipment not found."); equipmentStatus = rs.getString(1); }
                }
                if (!Set.of("AVAILABLE","BOOKED").contains(equipmentStatus.toUpperCase(Locale.ROOT))) throw bad("This equipment is not currently eligible for booking.");
                if (conflicts(c, request.equipmentId(), start, end, null)) throw new BookingException(409, "This time slot conflicts with an existing booking.");
                SharingService.ExternalTerms external=sharing.validateExternalBooking(c,user,request.equipmentId(),start,end);
                boolean approvalRequired = Boolean.parseBoolean(System.getenv().getOrDefault("BOOKING_APPROVAL_REQUIRED", "false"));
                String status = approvalRequired ? "PENDING_APPROVAL" : "CONFIRMED";
                long id;
                try (PreparedStatement p = c.prepareStatement("INSERT INTO bookings(instrument_id,agreement_id,user_id,start_time,end_time,purpose,status) VALUES(?,?,?,?,?,?,?) RETURNING id")) {
                    p.setLong(1, request.equipmentId());if(external==null)p.setNull(2,Types.BIGINT);else p.setLong(2,external.agreementId());p.setLong(3, user.id()); p.setObject(4, start); p.setObject(5, end); p.setString(6, purpose); p.setString(7, status);
                    try (ResultSet rs=p.executeQuery()) { rs.next(); id=rs.getLong(1); }
                }
                history(c,id,null,status,user.id(),user.name(),"Booking created.");
                Booking result = find(c,id);
                c.commit(); created=result;
            } catch (RuntimeException | SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("Could not create booking.", e); }
        broadcaster.publish(request.equipmentId());
        return created;
    }

    public Map<String,Object> mine(User user, String status, int page, int size) {
        int safePage=Math.max(0,page), safeSize=Math.min(100,Math.max(1,size));
        String sql="SELECT "+COLUMNS+" FROM bookings b JOIN instruments i ON i.id=b.instrument_id JOIN users u ON u.id=b.user_id WHERE b.user_id=?"+(status==null||status.isBlank()?"":" AND b.status=?")+" ORDER BY b.start_time DESC LIMIT ? OFFSET ?";
        try(Connection c=database.open(); PreparedStatement p=c.prepareStatement(sql)) { int n=1;p.setLong(n++,user.id());if(status!=null&&!status.isBlank())p.setString(n++,status.toUpperCase(Locale.ROOT));p.setInt(n++,safeSize);p.setLong(n,(long)safePage*safeSize);List<Booking> out=new ArrayList<>();try(ResultSet rs=p.executeQuery()){while(rs.next())out.add(map(rs));}
            String countSql="SELECT COUNT(*) FROM bookings WHERE user_id=?"+(status==null||status.isBlank()?"":" AND status=?");long total;try(PreparedStatement count=c.prepareStatement(countSql)){count.setLong(1,user.id());if(status!=null&&!status.isBlank())count.setString(2,status.toUpperCase(Locale.ROOT));try(ResultSet rs=count.executeQuery()){rs.next();total=rs.getLong(1);}}
            return Map.of("items",out,"page",safePage,"size",safeSize,"totalItems",total,"totalPages",(int)Math.ceil((double)total/safeSize));
        } catch(SQLException e){throw new IllegalStateException("Could not load bookings.",e);}
    }
    public List<Booking> pendingApprovals() { return adminBookingQuery("b.status='PENDING_APPROVAL'"); }
    public List<Booking> currentEquipmentUse() {
        return adminBookingQuery("b.status='IN_USE'");
    }
    private List<Booking> adminBookingQuery(String condition) {
        String sql="SELECT "+COLUMNS+" FROM bookings b JOIN instruments i ON i.id=b.instrument_id JOIN users u ON u.id=b.user_id WHERE "+condition+" ORDER BY b.start_time";
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement(sql);ResultSet rs=p.executeQuery()){
            List<Booking> result=new ArrayList<>();while(rs.next())result.add(map(rs));return result;
        }catch(SQLException e){throw new IllegalStateException("Could not load admin booking data.",e);}
    }
    public Booking get(long id, User user, boolean manager) { try(Connection c=database.open()){ Booking b=find(c,id); if(!manager&&b.userId()!=user.id())throw missing("Booking not found."); return b;}catch(SQLException e){throw new IllegalStateException(e);} }
    public List<BookingHistory> history(long id, User user, boolean manager) { get(id,user,manager); try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT h.id,h.previous_status,h.new_status,h.changed_by_name,h.changed_at,h.remarks FROM booking_history h WHERE h.booking_id=? ORDER BY h.changed_at,h.id")){p.setLong(1,id);try(ResultSet rs=p.executeQuery()){List<BookingHistory> out=new ArrayList<>();while(rs.next())out.add(new BookingHistory(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5,OffsetDateTime.class),rs.getString(6)));return out;}}catch(SQLException e){throw new IllegalStateException(e);} }
    public Booking cancel(long id, User actor, boolean manager, String reason) { return transition(id,actor,manager,"CANCELLED",reason,"cancel"); }
    public Booking approve(long id, User actor, boolean manager) { if(!manager)throw new BookingException(403,"Manager access is required."); return transition(id,actor,true,"CONFIRMED","Booking approved.","approve"); }
    public Booking reject(long id, User actor, boolean manager, String reason) { if(!manager)throw new BookingException(403,"Manager access is required."); if(reason==null||reason.isBlank())throw bad("A rejection reason is required."); return transition(id,actor,true,"CANCELLED",reason.trim(),"reject"); }
    private Booking transition(long id,User actor,boolean manager,String next,String remarks,String operation) {
        Booking changed;long equipmentId;
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            Booking b; try(PreparedStatement p=c.prepareStatement("SELECT "+COLUMNS+" FROM bookings b JOIN instruments i ON i.id=b.instrument_id JOIN users u ON u.id=b.user_id WHERE b.id=? FOR UPDATE OF b")){p.setLong(1,id);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Booking not found.");b=map(rs);}}
            equipmentId=b.equipmentId();
            if(!manager&&(actor==null||b.userId()!=actor.id()))throw missing("Booking not found.");
            String old=b.status();
            if(operation.equals("approve")||operation.equals("reject")){if(!old.equals("PENDING_APPROVAL"))throw bad("Only pending bookings can be approved or rejected.");}
            else {if(!Set.of("PENDING_APPROVAL","CONFIRMED").contains(old))throw bad("This booking can no longer be cancelled.");if(b.startTime().toInstant().isBefore(Instant.now()))throw bad("A booking that has started cannot be cancelled.");}
            try(PreparedStatement p=c.prepareStatement("UPDATE bookings SET status=?, approved_by=CASE WHEN ?='CONFIRMED' THEN ? ELSE approved_by END, approved_by_name=CASE WHEN ?='CONFIRMED' THEN ? ELSE approved_by_name END, approved_at=CASE WHEN ?='CONFIRMED' THEN CURRENT_TIMESTAMP ELSE approved_at END, rejection_reason=CASE WHEN ?='reject' THEN ? ELSE rejection_reason END, cancelled_at=CASE WHEN ?='cancel' OR ?='reject' THEN CURRENT_TIMESTAMP ELSE cancelled_at END, cancellation_reason=CASE WHEN ?='cancel' THEN ? ELSE cancellation_reason END WHERE id=?")){p.setString(1,next);p.setString(2,operation.equals("approve")?"CONFIRMED":"");if(actor==null)p.setNull(3,Types.BIGINT);else p.setLong(3,actor.id());p.setString(4,operation.equals("approve")?"CONFIRMED":"");p.setString(5,actor==null?"System Administrator":actor.name());p.setString(6,operation.equals("approve")?"CONFIRMED":"");p.setString(7,operation);p.setString(8,remarks);p.setString(9,operation);p.setString(10,operation);p.setString(11,operation);p.setString(12,remarks);p.setLong(13,id);p.executeUpdate();}
            history(c,id,old,next,actor==null?null:actor.id(),actor==null?"System Administrator":actor.name(),remarks);changed=find(c,id);c.commit();
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}catch(SQLException e){throw new IllegalStateException("Could not update booking.",e);}
        broadcaster.publish(equipmentId);
        return changed;
    }
    public List<Map<String,Object>> availability(long equipmentId, OffsetDateTime from, OffsetDateTime to){if(!to.isAfter(from))throw bad("End of date range must be after its start.");try(Connection c=database.open()){
        try(PreparedStatement equipment=c.prepareStatement("SELECT 1 FROM instruments WHERE id=?")){equipment.setLong(1,equipmentId);try(ResultSet rs=equipment.executeQuery()){if(!rs.next())throw missing("Equipment not found.");}}
        try(PreparedStatement p=c.prepareStatement("SELECT id,start_time,end_time,status FROM bookings WHERE instrument_id=? AND status IN ('PENDING_APPROVAL','CONFIRMED','IN_USE') AND start_time < ? AND end_time > ? ORDER BY start_time")){p.setLong(1,equipmentId);p.setObject(2,to.withOffsetSameInstant(ZoneOffset.UTC));p.setObject(3,from.withOffsetSameInstant(ZoneOffset.UTC));try(ResultSet rs=p.executeQuery()){List<Map<String,Object>> out=new ArrayList<>();while(rs.next())out.add(Map.of("bookingId",rs.getLong(1),"startTime",rs.getObject(2,OffsetDateTime.class),"endTime",rs.getObject(3,OffsetDateTime.class),"status",rs.getString(4)));return out;}}
    }catch(SQLException e){throw new IllegalStateException("Could not load equipment availability.",e);}}
    private boolean conflicts(Connection c,long equipment,OffsetDateTime start,OffsetDateTime end,Long exclude)throws SQLException{String sql="SELECT 1 FROM bookings WHERE instrument_id=? AND status IN ('PENDING_APPROVAL','CONFIRMED','IN_USE') AND start_time < ? AND end_time > ?"+(exclude==null?"":" AND id<>?")+" LIMIT 1";try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,equipment);p.setObject(2,end.withOffsetSameInstant(ZoneOffset.UTC));p.setObject(3,start.withOffsetSameInstant(ZoneOffset.UTC));if(exclude!=null)p.setLong(4,exclude);try(ResultSet rs=p.executeQuery()){return rs.next();}}}
    private static final String COLUMNS="b.id,b.instrument_id,i.name,b.user_id,u.name,u.email,b.start_time,b.end_time,b.purpose,b.status,b.approved_by,b.approved_by_name,b.approved_at,b.rejection_reason,b.cancelled_at,b.cancellation_reason,b.agreement_id,(SELECT fee_per_hour FROM sharing_agreements sa WHERE sa.id=b.agreement_id)";
    private Booking find(Connection c,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT "+COLUMNS+" FROM bookings b JOIN instruments i ON i.id=b.instrument_id JOIN users u ON u.id=b.user_id WHERE b.id=?")){p.setLong(1,id);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Booking not found.");return map(rs);}}}
    private static Booking map(ResultSet r)throws SQLException{long agreementIdValue=r.getLong(17);Long agreementId=r.wasNull()?null:agreementIdValue;java.math.BigDecimal rate=r.getBigDecimal(18);long minutes=Math.max(0,Duration.between(r.getObject(7,OffsetDateTime.class),r.getObject(8,OffsetDateTime.class)).toMinutes());java.math.BigDecimal estimate=rate==null?null:SharingService.fee(minutes,rate);return new Booking(r.getLong(1),r.getLong(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getObject(7,OffsetDateTime.class),r.getObject(8,OffsetDateTime.class),r.getString(9),r.getString(10),(Long)r.getObject(11),r.getString(12),r.getObject(13,OffsetDateTime.class),r.getString(14),r.getObject(15,OffsetDateTime.class),r.getString(16),agreementId,rate,estimate);}
    private static void history(Connection c,long id,String previous,String next,Long actor,String actorName,String remarks)throws SQLException{try(PreparedStatement p=c.prepareStatement("INSERT INTO booking_history(booking_id,previous_status,new_status,changed_by,changed_by_name,remarks) VALUES(?,?,?,?,?,?)")){p.setLong(1,id);p.setString(2,previous);p.setString(3,next);if(actor==null)p.setNull(4,Types.BIGINT);else p.setLong(4,actor);p.setString(5,actorName);p.setString(6,remarks);p.executeUpdate();}}
    private static OffsetDateTime parse(String value,String field){try{return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);}catch(Exception e){throw bad(field+" must be an ISO-8601 timestamp with a UTC offset.");}}
    private static BookingException bad(String m){return new BookingException(400,m);} private static BookingException missing(String m){return new BookingException(404,m);}
    public static class BookingException extends RuntimeException{private final int status;public BookingException(int s,String m){super(m);status=s;}public int status(){return status;}}
}
