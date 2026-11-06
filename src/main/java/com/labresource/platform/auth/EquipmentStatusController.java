package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class EquipmentStatusController {
    private final EquipmentUsageService usage;
    public EquipmentStatusController(EquipmentUsageService usage){this.usage=usage;}

    @GetMapping("/api/equipment/status")
    public ResponseEntity<?> publicStatus(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpSession session){
        if(!(session.getAttribute("authenticatedUser") instanceof User)&&!Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin")))return denied(false);
        return ResponseEntity.ok(usage.statusPage(status,page,size,false));
    }
    @GetMapping("/api/admin/equipment/status")
    public ResponseEntity<?> managerStatus(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpSession session){
        if(!Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin")))return denied(session.getAttribute("authenticatedUser") instanceof User);
        return ResponseEntity.ok(usage.statusPage(status,page,size,true));
    }
    private ResponseEntity<Map<String,String>> denied(boolean signedIn){return ResponseEntity.status(signedIn?403:401).body(Map.of("error",signedIn?"Manager access is required.":"Sign in to view equipment status."));}
}
