package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class InstitutionAdminController {
    private static final Set<String> ASSIGNABLE_ROLES=Set.of("RESEARCHER","STUDENT","DEPARTMENT_HEAD","LAB_MANAGER","INSTITUTION_ADMIN");
    private final PostgresDatabase database;
    public InstitutionAdminController(PostgresDatabase database) { this.database=database; }

    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestParam(required=false) String q,HttpSession session) {
        if(!isSystemAdmin(session))return denied();
        String term=q==null||q.isBlank()?null:"%"+q.trim()+"%";
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT u.id,u.name,u.email,u.role,i.id AS institution_id,i.name AS institution_name,u.is_approved FROM users u LEFT JOIN institutions i ON i.id=u.institution_id WHERE (?::text IS NULL OR u.email ILIKE ? OR u.name ILIKE ?) ORDER BY u.created_at DESC LIMIT 200")){
            p.setString(1,term);p.setString(2,term);p.setString(3,term);java.util.List<Map<String,Object>> result=new java.util.ArrayList<>();try(ResultSet rs=p.executeQuery()){while(rs.next()){Map<String,Object> row=new java.util.LinkedHashMap<>();row.put("id",rs.getLong(1));row.put("name",rs.getString(2));row.put("email",rs.getString(3));row.put("role",rs.getString(4));row.put("institutionId",rs.getObject(5));row.put("institutionName",rs.getString(6));row.put("approved",rs.getBoolean(7));result.add(row);}}return ResponseEntity.ok(Map.of("items",result));
        }catch(SQLException e){throw new IllegalStateException("Could not load accounts for system administration.",e);}
    }

    @PostMapping("/institutions")
    public ResponseEntity<?> createInstitution(@RequestBody(required=false) InstitutionBody body,HttpSession session) {
        if(!isSystemAdmin(session))return denied();
        String name=body==null||body.getName()==null?"":body.getName().trim();
        if(name.length()<2||name.length()>160)return ResponseEntity.badRequest().body(Map.of("error","Institution name must contain 2 to 160 characters."));
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("INSERT INTO institutions(name) VALUES (?) ON CONFLICT(name) DO NOTHING RETURNING id,name")){
            p.setString(1,name);try(ResultSet rs=p.executeQuery()){if(!rs.next())return ResponseEntity.status(409).body(Map.of("error","That institution already exists."));return ResponseEntity.status(201).body(new Institution(rs.getLong(1),rs.getString(2)));}
        }catch(SQLException e){throw new IllegalStateException("Could not create institution.",e);}
    }

    @PutMapping("/users/{userId}/affiliation")
    public ResponseEntity<?> setAffiliation(@PathVariable long userId,@RequestBody(required=false) AffiliationBody body,HttpSession session) {
        if(!isSystemAdmin(session))return denied();
        if(body==null||body.getRole()==null)return ResponseEntity.badRequest().body(Map.of("error","Institution and role are required."));
        String role=body.getRole().trim().toUpperCase(Locale.ROOT);
        if(!ASSIGNABLE_ROLES.contains(role))return ResponseEntity.badRequest().body(Map.of("error","Choose a permitted institutional role."));
        if(body.getInstitutionId()==null||body.getInstitutionId()<1)return ResponseEntity.badRequest().body(Map.of("error","A valid institutionId is required."));
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("UPDATE users SET institution_id=?::bigint,role=? WHERE id=? RETURNING id,name,email,institution_id,role")){
            p.setLong(1,body.getInstitutionId());p.setString(2,role);p.setLong(3,userId);try(ResultSet rs=p.executeQuery()){if(!rs.next())return ResponseEntity.notFound().build();return ResponseEntity.ok(Map.of("id",rs.getLong(1),"name",rs.getString(2),"email",rs.getString(3),"institutionId",rs.getLong(4),"role",rs.getString(5)));}
        }catch(SQLException e){if("23503".equals(e.getSQLState()))return ResponseEntity.badRequest().body(Map.of("error","Institution not found."));throw new IllegalStateException("Could not assign institutional role.",e);}
    }

    @PutMapping("/instruments/{equipmentId}/owner")
    public ResponseEntity<?> setEquipmentOwner(@PathVariable long equipmentId,@RequestBody(required=false) EquipmentOwnerBody body,HttpSession session) {
        if(!isSystemAdmin(session))return denied();
        if(body==null||body.getInstitutionId()==null||body.getInstitutionId()<1)return ResponseEntity.badRequest().body(Map.of("error","A valid owner institutionId is required."));
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("UPDATE instruments SET owner_institution_id=?,is_shareable=? WHERE id=? RETURNING id,owner_institution_id,is_shareable")){
            p.setLong(1,body.getInstitutionId());p.setBoolean(2,body.isShareable());p.setLong(3,equipmentId);try(ResultSet rs=p.executeQuery()){if(!rs.next())return ResponseEntity.notFound().build();return ResponseEntity.ok(Map.of("equipmentId",rs.getLong(1),"ownerInstitutionId",rs.getLong(2),"shareable",rs.getBoolean(3)));}
        }catch(SQLException e){if("23503".equals(e.getSQLState()))return ResponseEntity.badRequest().body(Map.of("error","Institution not found."));throw new IllegalStateException("Could not assign equipment owner.",e);}
    }

    private boolean isSystemAdmin(HttpSession session){return Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin"));}
    private ResponseEntity<Map<String,String>> denied(){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","System Administrator access is required."));}

    public static class InstitutionBody { private String name; public InstitutionBody(){} public String getName(){return name;} public void setName(String value){name=value;} }
    public static class AffiliationBody { private Long institutionId; private String role; public AffiliationBody(){} public Long getInstitutionId(){return institutionId;} public void setInstitutionId(Long v){institutionId=v;} public String getRole(){return role;} public void setRole(String v){role=v;} }
    public static class EquipmentOwnerBody { private Long institutionId; private boolean shareable; public EquipmentOwnerBody(){} public Long getInstitutionId(){return institutionId;} public void setInstitutionId(Long v){institutionId=v;} public boolean isShareable(){return shareable;} public void setShareable(boolean v){shareable=v;} }
}
