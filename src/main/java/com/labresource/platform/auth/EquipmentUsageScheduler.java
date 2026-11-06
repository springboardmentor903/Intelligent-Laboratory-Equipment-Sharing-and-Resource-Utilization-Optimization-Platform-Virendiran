package com.labresource.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class EquipmentUsageScheduler {
    private static final Logger log=LoggerFactory.getLogger(EquipmentUsageScheduler.class);
    private final PostgresDatabase database;
    private final EquipmentStatusBroadcaster broadcaster;
    public EquipmentUsageScheduler(PostgresDatabase database,EquipmentStatusBroadcaster broadcaster){this.database=database;this.broadcaster=broadcaster;}

    @Scheduled(fixedDelay=60_000,initialDelay=20_000)
    public void processMissedAndExpiredBookings(){
        try { processNoShows(); processExpiredUsage(); }
        catch(Exception ex){log.error("EQUIPMENT_USAGE_SCHEDULE_FAILED reason={}",ex.getMessage(),ex);}
    }

    private void processNoShows()throws SQLException{
        Set<Long> changedEquipment=new HashSet<>();int changed=0;
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            List<NoShow> rows=new ArrayList<>();
            try(PreparedStatement p=c.prepareStatement("SELECT id,instrument_id FROM bookings WHERE status='CONFIRMED' AND checked_in_at IS NULL AND start_time <= CURRENT_TIMESTAMP - INTERVAL '15 minutes' ORDER BY start_time LIMIT 100 FOR UPDATE SKIP LOCKED");ResultSet rs=p.executeQuery()){
                while(rs.next())rows.add(new NoShow(rs.getLong(1),rs.getLong(2)));
            }
            for(NoShow row:rows){
                try(PreparedStatement p=c.prepareStatement("UPDATE bookings SET status='NO_SHOW' WHERE id=? AND status='CONFIRMED' AND checked_in_at IS NULL")){p.setLong(1,row.bookingId);if(p.executeUpdate()==1){EquipmentUsageService.writeHistory(c,row.bookingId,"CONFIRMED","NO_SHOW",null,"System","Automatically marked No Show after the 15-minute check-in window.");changed++;changedEquipment.add(row.equipmentId);}}
            }
            c.commit();
        }catch(SQLException|RuntimeException ex){c.rollback();throw ex;}}
        for(long equipmentId:changedEquipment)broadcaster.publish(equipmentId);
        if(changed>0)log.info("BOOKING_NO_SHOWS_PROCESSED count={}",changed);
    }

    private void processExpiredUsage()throws SQLException{
        Set<Long> changedEquipment=new HashSet<>();int changed=0;
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            List<ExpiredUse> rows=new ArrayList<>();
            String select="SELECT b.id,b.instrument_id,b.end_time,ul.id FROM bookings b JOIN usage_logs ul ON ul.booking_id=b.id AND ul.actual_end_time IS NULL WHERE b.status='IN_USE' AND b.end_time<=CURRENT_TIMESTAMP ORDER BY b.end_time LIMIT 100 FOR UPDATE OF b,ul SKIP LOCKED";
            try(PreparedStatement p=c.prepareStatement(select);ResultSet rs=p.executeQuery()){
                while(rs.next())rows.add(new ExpiredUse(rs.getLong(1),rs.getLong(2),rs.getObject(3,OffsetDateTime.class),rs.getLong(4)));
            }
            for(ExpiredUse row:rows){
                try(PreparedStatement p=c.prepareStatement("UPDATE usage_logs SET actual_end_time=?,remarks=CASE WHEN COALESCE(remarks,'')='' THEN ? ELSE remarks || ' ' || ? END WHERE id=? AND actual_end_time IS NULL")){
                    p.setObject(1,row.scheduledEnd);p.setString(2,"Automatically closed at scheduled end; actual checkout was not recorded.");p.setString(3,"Automatically closed at scheduled end; actual checkout was not recorded.");p.setLong(4,row.usageLogId);if(p.executeUpdate()!=1)continue;
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE bookings SET status='COMPLETED',checked_out_at=? WHERE id=? AND status='IN_USE'")){p.setObject(1,row.scheduledEnd);p.setLong(2,row.bookingId);if(p.executeUpdate()!=1)throw new SQLException("Expired booking changed during automatic checkout.");}
                try(PreparedStatement p=c.prepareStatement("UPDATE instruments SET status='AVAILABLE' WHERE id=? AND UPPER(status)='BOOKED'")){p.setLong(1,row.equipmentId);p.executeUpdate();}
                EquipmentUsageService.writeHistory(c,row.bookingId,"IN_USE","COMPLETED",null,"System","Automatically checked out at the scheduled end time.");
                changed++;changedEquipment.add(row.equipmentId);
            }
            c.commit();
        }catch(SQLException|RuntimeException ex){c.rollback();throw ex;}}
        for(long equipmentId:changedEquipment)broadcaster.publish(equipmentId);
        if(changed>0)log.info("BOOKING_AUTO_CHECKOUTS_PROCESSED count={}",changed);
    }
    private record NoShow(long bookingId,long equipmentId){}
    private record ExpiredUse(long bookingId,long equipmentId,OffsetDateTime scheduledEnd,long usageLogId){}
}
