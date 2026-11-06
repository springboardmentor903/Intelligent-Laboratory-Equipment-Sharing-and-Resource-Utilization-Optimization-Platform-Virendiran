package com.labresource.platform.auth;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class SharingService {
    private static final Set<String> REQUESTER_ROLES=Set.of("RESEARCHER","STUDENT","DEPARTMENT_HEAD");
    private static final Set<String> OPEN_REQUEST_STATUSES=Set.of("PENDING","FORWARDED");
    private static final String REQUEST_COLUMNS="r.id,r.equipment_id,i.name,i.asset_tag,i.category,r.requester_id,ru.name,ru.email,r.requester_institution_id,ri.name,r.purpose,r.start_date,r.end_date,r.status,r.requester_reviewed_by,fr.name,r.requester_reviewed_at,r.requester_review_remarks,r.reviewed_by,lr.name,r.reviewed_at,r.review_remarks,a.id,r.created_at";

    private final PostgresDatabase database;
    private final EquipmentStatusBroadcaster broadcaster;
    private final ApplicationEventPublisher events;
    private final SharedOccupancyService occupancy;

    public SharingService(PostgresDatabase database,EquipmentStatusBroadcaster broadcaster,ApplicationEventPublisher events,SharedOccupancyService occupancy){
        this.database=database;this.broadcaster=broadcaster;this.events=events;this.occupancy=occupancy;
    }

    public List<Institution> institutions(){
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT id,name FROM institutions ORDER BY name");ResultSet rs=p.executeQuery()){
            List<Institution> result=new ArrayList<>();while(rs.next())result.add(new Institution(rs.getLong(1),rs.getString(2)));return result;
        }catch(SQLException e){throw failure("Could not load institutions.",e);}
    }

    public Map<String,Object> discover(User user,String institution,String category,String keyword,int page,int size){
        int p=Math.max(0,page),n=Math.min(50,Math.max(1,size));
        StringBuilder where=new StringBuilder(" WHERE l.is_active=TRUE AND i.is_shareable=TRUE AND i.owner_institution_id IS NOT NULL AND i.owner_institution_id<>COALESCE(?,0)");
        List<Object> values=new ArrayList<>();values.add(user.institutionId());
        if(institution!=null&&!institution.isBlank()){where.append(" AND ins.name ILIKE ?");values.add("%"+institution.trim()+"%");}
        if(category!=null&&!category.isBlank()){where.append(" AND i.category=?");values.add(category.trim());}
        if(keyword!=null&&!keyword.isBlank()){where.append(" AND (i.name ILIKE ? OR i.asset_tag ILIKE ? OR i.description ILIKE ? OR l.sharing_terms ILIKE ?)");String q="%"+keyword.trim()+"%";values.add(q);values.add(q);values.add(q);values.add(q);}
        String sql="SELECT i.id,i.name,i.asset_tag,i.category,i.description,i.location,ins.id,ins.name,l.fee_per_hour,l.external_share_percentage,l.minimum_notice_hours,l.maximum_booking_hours,l.sharing_terms,"+
            "(SELECT r.status FROM sharing_requests r WHERE r.equipment_id=i.id AND r.requester_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT 1),"+
            "(SELECT a.id FROM sharing_agreements a JOIN access_grants g ON g.agreement_id=a.id WHERE a.equipment_id=i.id AND g.user_id=? AND g.revoked_at IS NULL AND a.status='ACTIVE' AND a.end_date>=CURRENT_DATE ORDER BY a.end_date DESC LIMIT 1),"+
            "(SELECT a.end_date FROM sharing_agreements a JOIN access_grants g ON g.agreement_id=a.id WHERE a.equipment_id=i.id AND g.user_id=? AND g.revoked_at IS NULL AND a.status='ACTIVE' AND a.end_date>=CURRENT_DATE ORDER BY a.end_date DESC LIMIT 1) "+
            "FROM sharing_listings l JOIN instruments i ON i.id=l.equipment_id JOIN institutions ins ON ins.id=i.owner_institution_id"+where+" ORDER BY ins.name,i.category,i.name LIMIT ? OFFSET ?";
        try(Connection c=database.open();PreparedStatement pmt=c.prepareStatement(sql)){
            int x=1;pmt.setLong(x++,user.id());pmt.setLong(x++,user.id());pmt.setLong(x++,user.id());for(Object v:values){if(v==null)pmt.setNull(x++,Types.BIGINT);else if(v instanceof Long l)pmt.setObject(x++,l);else pmt.setString(x++,String.valueOf(v));}pmt.setInt(x++,n);pmt.setLong(x,(long)p*n);
            List<Map<String,Object>> items=new ArrayList<>();try(ResultSet rs=pmt.executeQuery()){while(rs.next()){Map<String,Object> item=new LinkedHashMap<>();item.put("equipmentId",rs.getLong(1));item.put("name",rs.getString(2));item.put("assetTag",rs.getString(3));item.put("category",rs.getString(4));item.put("description",rs.getString(5));item.put("location",rs.getString(6));item.put("ownerInstitutionId",rs.getLong(7));item.put("ownerInstitution",rs.getString(8));item.put("feePerHour",rs.getBigDecimal(9));item.put("externalSharePercentage",rs.getBigDecimal(10));item.put("minimumNoticeHours",rs.getInt(11));item.put("maximumBookingHours",rs.getInt(12));item.put("sharingTerms",rs.getString(13));item.put("latestRequestStatus",rs.getString(14));long aid=rs.getLong(15);item.put("activeAgreementId",rs.wasNull()?null:aid);item.put("activeAgreementEndDate",rs.getObject(16,LocalDate.class));items.add(item);}}
            String countSql="SELECT COUNT(*) FROM sharing_listings l JOIN instruments i ON i.id=l.equipment_id JOIN institutions ins ON ins.id=i.owner_institution_id"+where;long total;try(PreparedStatement count=c.prepareStatement(countSql)){int at=1;for(Object v:values){if(v==null)count.setNull(at++,Types.BIGINT);else if(v instanceof Long l)count.setObject(at++,l);else count.setString(at++,String.valueOf(v));}try(ResultSet rs=count.executeQuery()){rs.next();total=rs.getLong(1);}}
            return page(items,p,n,total);
        }catch(SQLException e){throw failure("Could not discover sharing listings.",e);}
    }

    public Map<String,Object> listing(long equipmentId,User actor,boolean systemAdmin){
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT l.*,i.name,i.asset_tag,i.owner_institution_id,i.is_shareable FROM sharing_listings l JOIN instruments i ON i.id=l.equipment_id WHERE l.equipment_id=?")){
            p.setLong(1,equipmentId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Sharing listing not found.");authorizeEquipmentManager(actor,systemAdmin,rs.getLong("owner_institution_id"));return listingMap(rs);}
        }catch(SQLException e){throw failure("Could not load sharing listing.",e);}
    }

    public Map<String,Object> saveListing(long equipmentId,SharingRequests.Listing body,User actor,boolean systemAdmin){
        if(body==null)throw bad("Listing details are required.");
        BigDecimal fee=body.getFeePerHour(),share=body.getExternalSharePercentage();Integer notice=body.getMinimumNoticeHours(),max=body.getMaximumBookingHours();String terms=body.getSharingTerms()==null?"":body.getSharingTerms().trim();
        if(fee==null||fee.signum()<0||fee.scale()>2||share==null||share.signum()<0||share.compareTo(BigDecimal.valueOf(100))>0||notice==null||notice<0||max==null||max<1||max>168||terms.isBlank()||terms.length()>2000)throw bad("Enter valid listing details: non-negative fee, share 0–100%, notice, maximum 1–168 hours, and terms up to 2000 characters.");
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            long owner;boolean shareable;
            try(PreparedStatement lock=c.prepareStatement("SELECT owner_institution_id,is_shareable FROM instruments WHERE id=? FOR UPDATE")){lock.setLong(1,equipmentId);try(ResultSet rs=lock.executeQuery()){if(!rs.next())throw missing("Equipment not found.");owner=rs.getLong(1);if(rs.wasNull())throw bad("Assign the equipment to an owner institution before sharing it.");shareable=rs.getBoolean(2);}}
            authorizeEquipmentManager(actor,systemAdmin,owner);if(!shareable)throw bad("Mark the equipment as shareable before creating a listing.");
            Boolean active=body.getActive();
            try(PreparedStatement upsert=c.prepareStatement("INSERT INTO sharing_listings(equipment_id,fee_per_hour,external_share_percentage,minimum_notice_hours,maximum_booking_hours,sharing_terms,is_active) VALUES(?,?,?,?,?,?,COALESCE(?,TRUE)) ON CONFLICT(equipment_id) DO UPDATE SET fee_per_hour=EXCLUDED.fee_per_hour,external_share_percentage=EXCLUDED.external_share_percentage,minimum_notice_hours=EXCLUDED.minimum_notice_hours,maximum_booking_hours=EXCLUDED.maximum_booking_hours,sharing_terms=EXCLUDED.sharing_terms,is_active=COALESCE(?,sharing_listings.is_active),updated_at=CURRENT_TIMESTAMP")){
                upsert.setLong(1,equipmentId);upsert.setBigDecimal(2,fee);upsert.setBigDecimal(3,share);upsert.setInt(4,notice);upsert.setInt(5,max);upsert.setString(6,terms);if(active==null)upsert.setNull(7,Types.BOOLEAN);else upsert.setBoolean(7,active);if(active==null)upsert.setNull(8,Types.BOOLEAN);else upsert.setBoolean(8,active);upsert.executeUpdate();
            }
            c.commit();
        }catch(RuntimeException|SQLException e){rollback(c);throw e;}}catch(SQLException e){throw failure("Could not save sharing listing.",e);}
        return listing(equipmentId,actor,systemAdmin);
    }

    public Map<String,Object> requestAccess(User user,long equipmentId,SharingRequests.AccessRequest body){
        if(user.institutionId()==null||!REQUESTER_ROLES.contains(user.role()))throw forbidden("An institution-affiliated Researcher, Student, or Department Head may request access.");
        if(body==null)throw bad("Request details are required.");String purpose=body.getPurpose()==null?"":body.getPurpose().trim();if(purpose.isEmpty()||purpose.length()>500)throw bad("Purpose is required and must not exceed 500 characters.");
        LocalDate start=parseDate(body.getStartDate(),"startDate"),end=parseDate(body.getEndDate(),"endDate");if(end.isBefore(start)||start.isBefore(LocalDate.now(ZoneId.of("Asia/Kolkata"))))throw bad("Choose a valid future date range.");
        long id;
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            long owner;
            try(PreparedStatement lock=c.prepareStatement("SELECT i.owner_institution_id FROM instruments i JOIN sharing_listings l ON l.equipment_id=i.id WHERE i.id=? AND i.is_shareable=TRUE AND l.is_active=TRUE FOR UPDATE OF i")){lock.setLong(1,equipmentId);try(ResultSet rs=lock.executeQuery()){if(!rs.next())throw missing("Active sharing listing not found.");owner=rs.getLong(1);}}
            if(owner==user.institutionId())throw bad("You cannot request access to equipment owned by your institution.");
            try(PreparedStatement dupe=c.prepareStatement("SELECT 1 FROM sharing_requests WHERE requester_id=? AND equipment_id=? AND status IN ('PENDING','FORWARDED') AND start_date<=? AND end_date>=? LIMIT 1")){dupe.setLong(1,user.id());dupe.setLong(2,equipmentId);dupe.setObject(3,end);dupe.setObject(4,start);try(ResultSet rs=dupe.executeQuery()){if(rs.next())throw conflict("An open request already overlaps those dates.");}}
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO sharing_requests(equipment_id,requester_id,requester_institution_id,purpose,start_date,end_date) VALUES(?,?,?,?,?,?) RETURNING id")){insert.setLong(1,equipmentId);insert.setLong(2,user.id());insert.setLong(3,user.institutionId());insert.setString(4,purpose);insert.setObject(5,start);insert.setObject(6,end);try(ResultSet rs=insert.executeQuery()){rs.next();id=rs.getLong(1);}}
            c.commit();
        }catch(RuntimeException|SQLException e){rollback(c);throw e;}}catch(SQLException e){throw failure("Could not create sharing access request.",e);}
        return requestMap(id);
    }

    public Map<String,Object> requests(String view,User user,String status,int page,int size){
        int p=Math.max(0,page),n=Math.min(100,Math.max(1,size));String where;
        switch(view){
            case "mine" -> where="r.requester_id=?";
            case "department" -> {requireRole(user,"DEPARTMENT_HEAD");where="r.requester_institution_id=?";}
            case "outgoing" -> {requireRole(user,"INSTITUTION_ADMIN");where="r.requester_institution_id=? AND r.status='PENDING'";}
            case "incoming" -> {requireRole(user,"INSTITUTION_ADMIN");where="i.owner_institution_id=? AND r.status='FORWARDED'";}
            default -> throw bad("Unknown request list.");
        }
        if(user.institutionId()==null&&!("mine".equals(view)))throw forbidden("Institution membership is required.");
        if("mine".equals(view)&&user.id()<1)throw forbidden("Sign in to view requests.");
        if(status!=null&&!status.isBlank()){String normalized=status.trim().toUpperCase(Locale.ROOT);if(!Set.of("PENDING","FORWARDED","APPROVED","REJECTED","WITHDRAWN").contains(normalized))throw bad("Unknown request status.");where+=" AND r.status=?";}
        String joins=" FROM sharing_requests r JOIN instruments i ON i.id=r.equipment_id JOIN users ru ON ru.id=r.requester_id JOIN institutions ri ON ri.id=r.requester_institution_id LEFT JOIN users fr ON fr.id=r.requester_reviewed_by LEFT JOIN users lr ON lr.id=r.reviewed_by LEFT JOIN sharing_agreements a ON a.request_id=r.id WHERE "+where;
        try(Connection c=database.open();PreparedStatement q=c.prepareStatement("SELECT "+REQUEST_COLUMNS+joins+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?")){
            int at=bindRequestFilter(q,view,user,status);q.setInt(at++,n);q.setLong(at,(long)p*n);List<SharingRequestView> rows=new ArrayList<>();try(ResultSet rs=q.executeQuery()){while(rs.next())rows.add(mapRequest(rs));}
            long total;try(PreparedStatement count=c.prepareStatement("SELECT COUNT(*) FROM sharing_requests r JOIN instruments i ON i.id=r.equipment_id WHERE "+where)){bindRequestFilter(count,view,user,status);try(ResultSet rs=count.executeQuery()){rs.next();total=rs.getLong(1);}}
            return page(rows,p,n,total);
        }catch(SQLException e){throw failure("Could not load sharing requests.",e);}
    }

    public SharingRequestView actOnRequest(long requestId,String action,User actor,String remarks,String allowedStartValue,String allowedEndValue){
        String clean=remarks==null?"":remarks.trim();if(("reject".equals(action))&&clean.isEmpty())throw bad("Rejection remarks are required.");if(clean.length()>1000)throw bad("Remarks must not exceed 1000 characters.");
        long agreementId=0;
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            long equipmentId,requesterId,requesterInstitution;LocalDate start,end;String status;
            try(PreparedStatement q=c.prepareStatement("SELECT equipment_id,requester_id,requester_institution_id,start_date,end_date,status FROM sharing_requests WHERE id=? FOR UPDATE")){q.setLong(1,requestId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw missing("Sharing request not found.");equipmentId=rs.getLong(1);requesterId=rs.getLong(2);requesterInstitution=rs.getLong(3);start=rs.getObject(4,LocalDate.class);end=rs.getObject(5,LocalDate.class);status=rs.getString(6);}}
            if("withdraw".equals(action)){
                if(requesterId!=actor.id())throw missing("Sharing request not found.");if(!Set.of("PENDING","FORWARDED").contains(status))throw conflict("Only pending or forwarded requests can be withdrawn.");
                try(PreparedStatement u=c.prepareStatement("UPDATE sharing_requests SET status='WITHDRAWN' WHERE id=?")){u.setLong(1,requestId);u.executeUpdate();}
            }else if("forward".equals(action)||("reject".equals(action)&&"PENDING".equals(status))){
                requireInstitutionAdmin(actor,requesterInstitution);if(!"PENDING".equals(status))throw conflict("Only pending requests can be reviewed by the requesting institution.");
                String next="forward".equals(action)?"FORWARDED":"REJECTED";
                try(PreparedStatement u=c.prepareStatement("UPDATE sharing_requests SET status=?,requester_reviewed_by=?,requester_reviewed_at=CURRENT_TIMESTAMP,requester_review_remarks=? WHERE id=? AND status='PENDING'")){u.setString(1,next);u.setLong(2,actor.id());u.setString(3,clean.isEmpty()?null:clean);u.setLong(4,requestId);if(u.executeUpdate()!=1)throw conflict("This request has already been processed.");}
            }else if("approve".equals(action)||("reject".equals(action)&&"FORWARDED".equals(status))){
                long ownerInstitution;
                try(PreparedStatement q=c.prepareStatement("SELECT owner_institution_id FROM instruments WHERE id=? FOR UPDATE")){q.setLong(1,equipmentId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw missing("Equipment not found.");ownerInstitution=rs.getLong(1);}}
                requireInstitutionAdmin(actor,ownerInstitution);if(!"FORWARDED".equals(status))throw conflict("Only forwarded requests can be reviewed by the equipment owner.");
                if("approve".equals(action)){
                    LocalTime allowedStart=parseOptionalTime(allowedStartValue),allowedEnd=parseOptionalTime(allowedEndValue);if((allowedStart==null)!=(allowedEnd==null)||allowedStart!=null&&!allowedEnd.isAfter(allowedStart))throw bad("Allowed hours must include a valid start time and a later end time, or be left blank.");
                    BigDecimal fee;try(PreparedStatement q=c.prepareStatement("SELECT fee_per_hour FROM sharing_listings WHERE equipment_id=?")){q.setLong(1,equipmentId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw conflict("The sharing listing no longer exists.");fee=rs.getBigDecimal(1);}}
                    try(PreparedStatement a=c.prepareStatement("INSERT INTO sharing_agreements(request_id,equipment_id,owner_institution_id,partner_institution_id,start_date,end_date,allowed_start,allowed_end,fee_per_hour,approved_by) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id")){a.setLong(1,requestId);a.setLong(2,equipmentId);a.setLong(3,ownerInstitution);a.setLong(4,requesterInstitution);a.setObject(5,start);a.setObject(6,end);if(allowedStart==null)a.setNull(7,Types.TIME);else a.setObject(7,allowedStart);if(allowedEnd==null)a.setNull(8,Types.TIME);else a.setObject(8,allowedEnd);a.setBigDecimal(9,fee);a.setLong(10,actor.id());try(ResultSet rs=a.executeQuery()){rs.next();agreementId=rs.getLong(1);}}
                    try(PreparedStatement g=c.prepareStatement("INSERT INTO access_grants(agreement_id,user_id) VALUES(?,?)")){g.setLong(1,agreementId);g.setLong(2,requesterId);g.executeUpdate();}
                }
                String next="approve".equals(action)?"APPROVED":"REJECTED";
                try(PreparedStatement u=c.prepareStatement("UPDATE sharing_requests SET status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_remarks=? WHERE id=? AND status='FORWARDED'")){u.setString(1,next);u.setLong(2,actor.id());u.setString(3,clean.isEmpty()?null:clean);u.setLong(4,requestId);if(u.executeUpdate()!=1)throw conflict("This request has already been processed.");}
            }else throw conflict("This request cannot be changed in its current status.");
            c.commit();
        }catch(RuntimeException|SQLException e){rollback(c);throw e;}}catch(SQLException e){throw failure("Could not update sharing request.",e);}
        return requestMap(requestId);
    }

    public Map<String,Object> agreements(User user,boolean systemAdmin,boolean activeOnly){
        boolean institutionAdmin=user!=null&&"INSTITUTION_ADMIN".equals(user.role());
        StringBuilder sql=new StringBuilder("SELECT a.id,a.request_id,a.equipment_id,i.name,i.asset_tag,i.category,a.owner_institution_id,oi.name,a.partner_institution_id,pi.name,a.start_date,a.end_date,a.allowed_start,a.allowed_end,a.fee_per_hour,a.status,a.approved_by,ap.name,a.approved_at,a.revoked_by,a.revoked_at,a.revocation_reason,EXISTS(SELECT 1 FROM access_grants g WHERE g.agreement_id=a.id AND g.user_id=? AND g.revoked_at IS NULL) AS has_grant FROM sharing_agreements a JOIN instruments i ON i.id=a.equipment_id JOIN institutions oi ON oi.id=a.owner_institution_id JOIN institutions pi ON pi.id=a.partner_institution_id JOIN users ap ON ap.id=a.approved_by WHERE ");
        if(systemAdmin)sql.append("TRUE");else if(institutionAdmin)sql.append("(a.owner_institution_id=? OR a.partner_institution_id=? OR EXISTS(SELECT 1 FROM access_grants gx WHERE gx.agreement_id=a.id AND gx.user_id=? AND gx.revoked_at IS NULL))");else sql.append("EXISTS(SELECT 1 FROM access_grants gx WHERE gx.agreement_id=a.id AND gx.user_id=? AND gx.revoked_at IS NULL)");
        if(activeOnly)sql.append(" AND a.status='ACTIVE' AND a.end_date>=CURRENT_DATE");sql.append(" ORDER BY a.end_date DESC,a.id DESC");
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement(sql.toString())){p.setLong(1,user==null?0:user.id());if(!systemAdmin){int at=2;if(institutionAdmin){Long inst=user.institutionId()==null?0L:user.institutionId();p.setLong(at++,inst);p.setLong(at++,inst);}p.setLong(at,user.id());}List<Map<String,Object>> out=new ArrayList<>();try(ResultSet rs=p.executeQuery()){while(rs.next())out.add(agreementMap(rs));}return Map.of("items",out);}
        catch(SQLException e){throw failure("Could not load sharing agreements.",e);}
    }

    public Map<String,Object> agreementGrants(long agreementId,User actor,boolean systemAdmin){
        try(Connection c=database.open()){
            requireAgreementManager(c,agreementId,actor,systemAdmin);
            try(PreparedStatement p=c.prepareStatement("SELECT g.id,g.user_id,u.name,u.email,g.granted_at,g.revoked_at,g.revocation_reason FROM access_grants g JOIN users u ON u.id=g.user_id WHERE g.agreement_id=? ORDER BY g.granted_at,g.id")){p.setLong(1,agreementId);List<Map<String,Object>> items=new ArrayList<>();try(ResultSet rs=p.executeQuery()){while(rs.next()){Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong(1));m.put("userId",rs.getLong(2));m.put("userName",rs.getString(3));m.put("userEmail",rs.getString(4));m.put("grantedAt",rs.getObject(5,OffsetDateTime.class));m.put("revokedAt",rs.getObject(6,OffsetDateTime.class));m.put("revocationReason",rs.getString(7));items.add(m);}}return Map.of("items",items);}
        }catch(SQLException e){throw failure("Could not load agreement grants.",e);}
    }

    public void revokeAgreement(long agreementId,User actor,String reason,boolean systemAdmin){
        String why=requiredReason(reason);Set<Long> equipment=new LinkedHashSet<>();List<ReleasedTimeEvent> released=new ArrayList<>();
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            requireAgreementManager(c,agreementId,actor,systemAdmin);
            try(PreparedStatement q=c.prepareStatement("SELECT equipment_id FROM sharing_agreements WHERE id=? FOR UPDATE")){q.setLong(1,agreementId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw missing("Agreement not found.");equipment.add(rs.getLong(1));}}
            try(PreparedStatement u=c.prepareStatement("UPDATE sharing_agreements SET status='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,revocation_reason=? WHERE id=? AND status='ACTIVE'")){if(actor==null)u.setNull(1,Types.BIGINT);else u.setLong(1,actor.id());u.setString(2,why);u.setLong(3,agreementId);if(u.executeUpdate()==0)throw conflict("Agreement is already revoked.");}
            released.addAll(cancelFuture(c,"agreement_id=?",agreementId,null,why,actor,equipment));c.commit();
        }catch(RuntimeException|SQLException e){rollback(c);throw e;}}catch(SQLException e){throw failure("Could not revoke agreement.",e);}
        publishReleased(equipment,released);
    }

    public void revokeGrant(long agreementId,long grantId,User actor,String reason,boolean systemAdmin){
        String why=requiredReason(reason);Set<Long> equipment=new LinkedHashSet<>();List<ReleasedTimeEvent> released=new ArrayList<>();
        try(Connection c=database.open()){c.setAutoCommit(false);try{
            requireAgreementManager(c,agreementId,actor,systemAdmin);long userId;
            try(PreparedStatement q=c.prepareStatement("SELECT user_id FROM access_grants WHERE id=? AND agreement_id=? FOR UPDATE")){q.setLong(1,grantId);q.setLong(2,agreementId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw missing("Access grant not found.");userId=rs.getLong(1);}}
            try(PreparedStatement q=c.prepareStatement("SELECT equipment_id FROM sharing_agreements WHERE id=? FOR UPDATE")){q.setLong(1,agreementId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw missing("Agreement not found.");equipment.add(rs.getLong(1));}}
            try(PreparedStatement u=c.prepareStatement("UPDATE access_grants SET revoked_by=?,revoked_at=CURRENT_TIMESTAMP,revocation_reason=? WHERE id=? AND revoked_at IS NULL")){if(actor==null)u.setNull(1,Types.BIGINT);else u.setLong(1,actor.id());u.setString(2,why);u.setLong(3,grantId);if(u.executeUpdate()==0)throw conflict("Access grant is already revoked.");}
            released.addAll(cancelFuture(c,"agreement_id=? AND user_id=?",agreementId,userId,why,actor,equipment));c.commit();
        }catch(RuntimeException|SQLException e){rollback(c);throw e;}}catch(SQLException e){throw failure("Could not revoke access grant.",e);}
        publishReleased(equipment,released);
    }

    public Map<String,Object> usageFees(long agreementId,User user,boolean systemAdmin,LocalDate from,LocalDate to){
        if(from!=null&&to!=null&&to.isBefore(from))throw bad("The period end must not be before its start.");
        try(Connection c=database.open()){
            requireAgreementViewer(c,agreementId,user,systemAdmin);
            String period=" WHERE b.agreement_id=?"+(from==null?"":" AND b.start_time>=?::date")+(to==null?"":" AND b.start_time<(?::date+INTERVAL '1 day')");
            String sql="SELECT b.id,b.start_time,b.end_time,b.status,a.fee_per_hour,COALESCE(SUM(CASE WHEN ul.actual_end_time IS NOT NULL THEN FLOOR(EXTRACT(EPOCH FROM (ul.actual_end_time-ul.actual_start_time))/60)::bigint ELSE 0 END),0) used_minutes FROM bookings b JOIN sharing_agreements a ON a.id=b.agreement_id LEFT JOIN usage_logs ul ON ul.booking_id=b.id"+period+" GROUP BY b.id,a.fee_per_hour ORDER BY b.start_time DESC";
            List<Map<String,Object>> items=new ArrayList<>();long totalBooked=0,totalUsed=0;BigDecimal estimated=BigDecimal.ZERO,actual=BigDecimal.ZERO;boolean allComplete=true;
            try(PreparedStatement p=c.prepareStatement(sql)){int i=1;p.setLong(i++,agreementId);if(from!=null)p.setObject(i++,from);if(to!=null)p.setObject(i++,to);try(ResultSet rs=p.executeQuery()){while(rs.next()){
                long booked=Math.max(0,Duration.between(rs.getObject(2,OffsetDateTime.class),rs.getObject(3,OffsetDateTime.class)).toMinutes());long used=rs.getLong(6);BigDecimal rate=rs.getBigDecimal(5);BigDecimal estimatedFee=fee(booked,rate);boolean complete="COMPLETED".equals(rs.getString(4));BigDecimal actualFee=complete?fee(used,rate):null;
                Map<String,Object> item=new LinkedHashMap<>();item.put("bookingId",rs.getLong(1));item.put("bookingDate",rs.getObject(2,OffsetDateTime.class));item.put("status",rs.getString(4));item.put("bookedMinutes",booked);item.put("usedMinutes",used);item.put("feePerHour",rate);item.put("estimatedFee",estimatedFee);item.put("actualFee",actualFee);items.add(item);totalBooked+=booked;totalUsed+=used;estimated=estimated.add(BigDecimal.valueOf(booked).multiply(rate).divide(BigDecimal.valueOf(60),8,RoundingMode.HALF_UP));if(complete)actual=actual.add(BigDecimal.valueOf(used).multiply(rate).divide(BigDecimal.valueOf(60),8,RoundingMode.HALF_UP));else allComplete=false;
            }}}
            Map<String,Object> out=new LinkedHashMap<>();out.put("agreementId",agreementId);out.put("items",items);out.put("totalBookedMinutes",totalBooked);out.put("totalUsedMinutes",totalUsed);out.put("totalEstimatedFee",estimated.setScale(2,RoundingMode.HALF_UP));out.put("totalActualFee",allComplete?actual.setScale(2,RoundingMode.HALF_UP):null);return out;
        }catch(SQLException e){throw failure("Could not calculate usage fees.",e);}
    }

    public Map<String,Object> sharedSchedule(long equipmentId,OffsetDateTime from,OffsetDateTime to,User user,boolean systemAdmin){
        if(from==null||to==null||!to.isAfter(from)||Duration.between(from,to).toDays()>62)throw bad("Choose a valid schedule range of at most 62 days.");
        return occupancy.occupancy(equipmentId,from,to,user,systemAdmin);
    }

    public ExternalTerms validateExternalBooking(Connection c,User user,long equipmentId,OffsetDateTime start,OffsetDateTime end)throws SQLException{
        if(user==null||user.institutionId()==null)return null;
        long owner;try(PreparedStatement q=c.prepareStatement("SELECT owner_institution_id FROM instruments WHERE id=?")){q.setLong(1,equipmentId);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw missing("Equipment not found.");owner=rs.getLong(1);if(rs.wasNull())return null;}}
        if(owner==user.institutionId())return null;
        String sql="SELECT a.id,a.start_date,a.end_date,a.allowed_start,a.allowed_end,a.fee_per_hour,l.minimum_notice_hours,l.maximum_booking_hours,l.external_share_percentage FROM sharing_agreements a JOIN access_grants g ON g.agreement_id=a.id JOIN sharing_listings l ON l.equipment_id=a.equipment_id WHERE a.equipment_id=? AND a.partner_institution_id=? AND g.user_id=? AND g.revoked_at IS NULL AND a.status='ACTIVE' AND a.end_date>=CURRENT_DATE ORDER BY a.end_date DESC LIMIT 1 FOR UPDATE OF a";
        long agreementId;LocalDate agreementStart,agreementEnd;LocalTime allowedStart,allowedEnd;BigDecimal rate,share;int notice,maxHours;
        try(PreparedStatement q=c.prepareStatement(sql)){q.setLong(1,equipmentId);q.setLong(2,user.institutionId());q.setLong(3,user.id());try(ResultSet rs=q.executeQuery()){if(!rs.next())throw forbidden("An active agreement and unrevoked access grant are required for external booking.");agreementId=rs.getLong(1);agreementStart=rs.getObject(2,LocalDate.class);agreementEnd=rs.getObject(3,LocalDate.class);allowedStart=rs.getObject(4,LocalTime.class);allowedEnd=rs.getObject(5,LocalTime.class);rate=rs.getBigDecimal(6);notice=rs.getInt(7);maxHours=rs.getInt(8);share=rs.getBigDecimal(9);}}
        if(start.toInstant().isBefore(agreementStart.atStartOfDay(SharedOccupancyService.PLATFORM_ZONE).toInstant())||end.toInstant().isAfter(agreementEnd.plusDays(1).atStartOfDay(SharedOccupancyService.PLATFORM_ZONE).toInstant()))throw forbidden("The entire booking must fall within the agreement dates.");
        if(allowedStart!=null){ZonedDateTime localStart=start.atZoneSameInstant(SharedOccupancyService.PLATFORM_ZONE),localEnd=end.atZoneSameInstant(SharedOccupancyService.PLATFORM_ZONE);if(!localStart.toLocalDate().equals(localEnd.toLocalDate())||localStart.toLocalTime().isBefore(allowedStart)||localEnd.toLocalTime().isAfter(allowedEnd))throw forbidden("The booking must fit entirely within the agreement's allowed hours.");}
        if(start.toInstant().isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusHours(notice).toInstant()))throw bad("This listing requires at least "+notice+" hours of booking notice.");
        long duration=Duration.between(start,end).toMinutes();int effectiveMax=Math.min(8,maxHours);if(duration>effectiveMax*60L)throw bad("External bookings may not exceed "+effectiveMax+" hours.");
        LocalDate firstWeek=SharedOccupancyService.weekStart(start.atZoneSameInstant(SharedOccupancyService.PLATFORM_ZONE).toLocalDate()),lastWeek=SharedOccupancyService.weekStart(end.minusNanos(1).atZoneSameInstant(SharedOccupancyService.PLATFORM_ZONE).toLocalDate());
        for(LocalDate week=firstWeek;!week.isAfter(lastWeek);week=week.plusWeeks(1)){
            OffsetDateTime weekStart=week.atStartOfDay(SharedOccupancyService.PLATFORM_ZONE).toOffsetDateTime(),weekEnd=week.plusWeeks(1).atStartOfDay(SharedOccupancyService.PLATFORM_ZONE).toOffsetDateTime();OffsetDateTime newStart=start.isAfter(weekStart)?start:weekStart,newEnd=end.isBefore(weekEnd)?end:weekEnd;
            long requested=occupancy.operatingMinutes(newStart,newEnd),used=occupancy.externalMinutes(c,equipmentId,week);long limit=BigDecimal.valueOf(occupancy.operatingCapacityMinutes(week)).multiply(share).divide(BigDecimal.valueOf(100),0,RoundingMode.DOWN).longValue();if(used+requested>limit)throw conflict("The weekly external share limit has been reached.");
        }
        return new ExternalTerms(agreementId,rate,fee(duration,rate));
    }

    private List<ReleasedTimeEvent> cancelFuture(Connection c,String filter,long agreementId,Long userId,String reason,User actor,Set<Long> equipment)throws SQLException{
        String sql="SELECT id,instrument_id,start_time,end_time,status FROM bookings WHERE "+filter+" AND status IN ('PENDING_APPROVAL','CONFIRMED') AND start_time>CURRENT_TIMESTAMP FOR UPDATE";
        List<ReleasedTimeEvent> released=new ArrayList<>();try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,agreementId);if(userId!=null)p.setLong(2,userId);try(ResultSet rs=p.executeQuery()){while(rs.next()){long booking=rs.getLong(1),instrument=rs.getLong(2);OffsetDateTime start=rs.getObject(3,OffsetDateTime.class),end=rs.getObject(4,OffsetDateTime.class);String previousStatus=rs.getString(5);equipment.add(instrument);
            try(PreparedStatement update=c.prepareStatement("UPDATE bookings SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,cancellation_reason=? WHERE id=?")){update.setString(1,"Shared access revoked: "+reason);update.setLong(2,booking);update.executeUpdate();}
            EquipmentUsageService.writeHistory(c,booking,previousStatus,"CANCELLED",actor==null?null:actor.id(),actor==null?"System Administrator":actor.name(),"Shared access revoked: "+reason);released.add(new ReleasedTimeEvent(instrument,booking,start,end));
        }}}return released;
    }

    private void publishReleased(Set<Long> equipment,List<ReleasedTimeEvent> released){for(long id:equipment)broadcaster.publish(id);for(ReleasedTimeEvent event:released)events.publishEvent(event);}
    private void requireAgreementManager(Connection c,long agreementId,User actor,boolean systemAdmin)throws SQLException{
        if(systemAdmin)return;if(actor==null||!"INSTITUTION_ADMIN".equals(actor.role()))throw forbidden("An owner Institution Administrator is required.");
        try(PreparedStatement p=c.prepareStatement("SELECT owner_institution_id FROM sharing_agreements WHERE id=?")){p.setLong(1,agreementId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Agreement not found.");if(actor.institutionId()==null||actor.institutionId()!=rs.getLong(1))throw missing("Agreement not found.");}}
    }
    private void requireAgreementViewer(Connection c,long agreementId,User user,boolean systemAdmin)throws SQLException{
        if(systemAdmin)return;if(user==null)throw forbidden("Sign in is required.");
        try(PreparedStatement p=c.prepareStatement("SELECT a.owner_institution_id,a.partner_institution_id,EXISTS(SELECT 1 FROM access_grants g WHERE g.agreement_id=a.id AND g.user_id=? AND g.revoked_at IS NULL) FROM sharing_agreements a WHERE a.id=?")){p.setLong(1,user.id());p.setLong(2,agreementId);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Agreement not found.");boolean institutionAdmin="INSTITUTION_ADMIN".equals(user.role())&&user.institutionId()!=null&&(user.institutionId()==rs.getLong(1)||user.institutionId()==rs.getLong(2));if(!rs.getBoolean(3)&&!institutionAdmin)throw missing("Agreement not found.");}}
    }
    private void authorizeEquipmentManager(User actor,boolean systemAdmin,long ownerInstitution){if(systemAdmin)return;if(actor==null||!"INSTITUTION_ADMIN".equals(actor.role())||actor.institutionId()==null||actor.institutionId()!=ownerInstitution)throw forbidden("Only the equipment owner's Institution Administrator may manage this listing.");}
    private void requireInstitutionAdmin(User actor,long institutionId){if(actor==null||!"INSTITUTION_ADMIN".equals(actor.role())||actor.institutionId()==null||actor.institutionId()!=institutionId)throw forbidden("Institution Administrator access for the relevant institution is required.");}
    private void requireRole(User actor,String role){if(actor==null||!role.equals(actor.role()))throw forbidden(role.replace('_',' ') + " access is required.");}
    private void bindRequestFilter(PreparedStatement p,String view,User user,String status)throws SQLException{int n=1;p.setLong(n++,"mine".equals(view)?user.id():user.institutionId());if(status!=null&&!status.isBlank())p.setString(n,status.trim().toUpperCase(Locale.ROOT));}
    private Map<String,Object> requestMap(long id){try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT "+REQUEST_COLUMNS+" FROM sharing_requests r JOIN instruments i ON i.id=r.equipment_id JOIN users ru ON ru.id=r.requester_id JOIN institutions ri ON ri.id=r.requester_institution_id LEFT JOIN users fr ON fr.id=r.requester_reviewed_by LEFT JOIN users lr ON lr.id=r.reviewed_by LEFT JOIN sharing_agreements a ON a.request_id=r.id WHERE r.id=?")){p.setLong(1,id);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw missing("Sharing request not found.");return requestMap(mapRequest(rs));}}catch(SQLException e){throw failure("Could not load sharing request.",e);}}
    private Map<String,Object> requestMap(SharingRequestView r){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.id());m.put("equipmentId",r.equipmentId());m.put("equipmentName",r.equipmentName());m.put("assetTag",r.assetTag());m.put("category",r.category());m.put("requesterId",r.requesterId());m.put("requesterName",r.requesterName());m.put("requesterEmail",r.requesterEmail());m.put("requesterInstitutionId",r.requesterInstitutionId());m.put("requesterInstitution",r.requesterInstitution());m.put("purpose",r.purpose());m.put("startDate",r.startDate());m.put("endDate",r.endDate());m.put("status",r.status());m.put("requesterReviewedBy",r.requesterReviewedBy());m.put("requesterReviewerName",r.requesterReviewerName());m.put("requesterReviewedAt",r.requesterReviewedAt());m.put("requesterReviewRemarks",r.requesterReviewRemarks());m.put("reviewedBy",r.reviewedBy());m.put("reviewerName",r.reviewerName());m.put("reviewedAt",r.reviewedAt());m.put("reviewRemarks",r.reviewRemarks());m.put("agreementId",r.agreementId());m.put("createdAt",r.createdAt());return m;}
    private SharingRequestView mapRequest(ResultSet r)throws SQLException{long first=r.getLong(15);Long firstId=r.wasNull()?null:first;long finalIdValue=r.getLong(19);Long finalId=r.wasNull()?null:finalIdValue;long agreement=r.getLong(23);Long agreementId=r.wasNull()?null:agreement;return new SharingRequestView(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getString(7),r.getString(8),r.getLong(9),r.getString(10),r.getString(11),r.getObject(12,LocalDate.class),r.getObject(13,LocalDate.class),r.getString(14),firstId,r.getString(16),r.getObject(17,OffsetDateTime.class),r.getString(18),finalId,r.getString(20),r.getObject(21,OffsetDateTime.class),r.getString(22),agreementId,r.getObject(24,OffsetDateTime.class));}
    private Map<String,Object> listingMap(ResultSet rs)throws SQLException{Map<String,Object> m=new LinkedHashMap<>();m.put("equipmentId",rs.getLong("equipment_id"));m.put("equipmentName",rs.getString("name"));m.put("assetTag",rs.getString("asset_tag"));m.put("shareable",rs.getBoolean("is_shareable"));m.put("feePerHour",rs.getBigDecimal("fee_per_hour"));m.put("externalSharePercentage",rs.getBigDecimal("external_share_percentage"));m.put("minimumNoticeHours",rs.getInt("minimum_notice_hours"));m.put("maximumBookingHours",rs.getInt("maximum_booking_hours"));m.put("sharingTerms",rs.getString("sharing_terms"));m.put("active",rs.getBoolean("is_active"));return m;}
    private Map<String,Object> agreementMap(ResultSet r)throws SQLException{Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getLong(1));m.put("requestId",r.getLong(2));m.put("equipmentId",r.getLong(3));m.put("equipmentName",r.getString(4));m.put("assetTag",r.getString(5));m.put("category",r.getString(6));m.put("ownerInstitutionId",r.getLong(7));m.put("ownerInstitution",r.getString(8));m.put("partnerInstitutionId",r.getLong(9));m.put("partnerInstitution",r.getString(10));m.put("startDate",r.getObject(11,LocalDate.class));m.put("endDate",r.getObject(12,LocalDate.class));m.put("allowedStart",r.getObject(13,LocalTime.class));m.put("allowedEnd",r.getObject(14,LocalTime.class));m.put("feePerHour",r.getBigDecimal(15));m.put("status",r.getString(16));m.put("approvedBy",r.getLong(17));m.put("approvedByName",r.getString(18));m.put("approvedAt",r.getObject(19,OffsetDateTime.class));m.put("revokedBy",r.getObject(20));m.put("revokedAt",r.getObject(21,OffsetDateTime.class));m.put("revocationReason",r.getString(22));m.put("hasAccessGrant",r.getBoolean(23));return m;}
    private Map<String,Object> page(List<?> items,int page,int size,long total){Map<String,Object> m=new LinkedHashMap<>();m.put("items",items);m.put("page",page);m.put("size",size);m.put("totalItems",total);m.put("totalPages",(int)Math.ceil((double)total/size));return m;}
    static BigDecimal fee(long minutes,BigDecimal rate){return BigDecimal.valueOf(minutes).multiply(rate).divide(BigDecimal.valueOf(60),2,RoundingMode.HALF_UP);}
    private LocalDate parseDate(String value,String field){try{return LocalDate.parse(value);}catch(Exception e){throw bad(field+" must be an ISO date (YYYY-MM-DD).");}}
    private LocalTime parseOptionalTime(String value){if(value==null||value.isBlank())return null;try{return LocalTime.parse(value.trim());}catch(Exception e){throw bad("Allowed hours must use HH:mm or HH:mm:ss.");}}
    private String requiredReason(String r){String v=r==null?"":r.trim();if(v.isEmpty())throw bad("A revocation reason is required.");if(v.length()>900)throw bad("The reason must not exceed 900 characters.");return v;}
    private static void rollback(Connection c){try{c.rollback();}catch(SQLException ignored){}}
    private IllegalStateException failure(String m,SQLException e){return new IllegalStateException(m,e);}
    private BookingService.BookingException bad(String m){return new BookingService.BookingException(400,m);}
    private BookingService.BookingException forbidden(String m){return new BookingService.BookingException(403,m);}
    private BookingService.BookingException conflict(String m){return new BookingService.BookingException(409,m);}
    private BookingService.BookingException missing(String m){return new BookingService.BookingException(404,m);}

    public record ReleasedTimeEvent(long equipmentId,long bookingId,OffsetDateTime startTime,OffsetDateTime endTime) { }
    public record ExternalTerms(long agreementId,BigDecimal feePerHour,BigDecimal estimatedFee) { }
}
