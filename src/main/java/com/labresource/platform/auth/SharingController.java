package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/sharing")
public class SharingController {
    private final SharingService sharing;
    public SharingController(SharingService sharing){this.sharing=sharing;}

    @GetMapping("/listings")
    public ResponseEntity<?> discover(@RequestParam(required=false) String institution,@RequestParam(required=false) String category,
        @RequestParam(required=false) String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,HttpSession session){
        User user=user(session);if(user==null)return unauthorized();return ResponseEntity.ok(sharing.discover(user,institution,category,q,page,size));
    }

    @GetMapping("/listings/{equipmentId}")
    public ResponseEntity<?> listing(@PathVariable long equipmentId,HttpSession session){User actor=user(session);boolean sys=systemAdmin(session);if(actor==null&&!sys)return unauthorized();return ResponseEntity.ok(sharing.listing(equipmentId,actor,sys));}

    @PutMapping("/listings/{equipmentId}")
    public ResponseEntity<?> saveListing(@PathVariable long equipmentId,@RequestBody(required=false) SharingRequests.Listing body,HttpSession session){User actor=user(session);boolean sys=systemAdmin(session);if(actor==null&&!sys)return unauthorized();return ResponseEntity.ok(sharing.saveListing(equipmentId,body,actor,sys));}

    @PostMapping("/listings/{equipmentId}/requests")
    public ResponseEntity<?> createRequest(@PathVariable long equipmentId,@RequestBody(required=false) SharingRequests.AccessRequest body,HttpSession session){User actor=user(session);if(actor==null)return unauthorized();return ResponseEntity.status(HttpStatus.CREATED).body(sharing.requestAccess(actor,equipmentId,body));}

    @GetMapping("/requests/{view}")
    public ResponseEntity<?> requests(@PathVariable String view,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpSession session){User actor=user(session);if(actor==null)return unauthorized();return ResponseEntity.ok(sharing.requests(view,actor,status,page,size));}

    @PostMapping("/requests/{requestId}/forward")
    public ResponseEntity<?> forward(@PathVariable long requestId,@RequestBody(required=false) SharingRequests.Review body,HttpSession session){User actor=user(session);if(actor==null)return unauthorized();return ResponseEntity.ok(sharing.actOnRequest(requestId,"forward",actor,body==null?null:body.getRemarks(),null,null));}

    @PostMapping("/requests/{requestId}/approve")
    public ResponseEntity<?> approve(@PathVariable long requestId,@RequestBody(required=false) SharingRequests.Review body,HttpSession session){User actor=user(session);if(actor==null)return unauthorized();return ResponseEntity.ok(sharing.actOnRequest(requestId,"approve",actor,body==null?null:body.getRemarks(),body==null?null:body.getAllowedStart(),body==null?null:body.getAllowedEnd()));}

    @PostMapping("/requests/{requestId}/reject")
    public ResponseEntity<?> reject(@PathVariable long requestId,@RequestBody(required=false) SharingRequests.Review body,HttpSession session){User actor=user(session);if(actor==null)return unauthorized();return ResponseEntity.ok(sharing.actOnRequest(requestId,"reject",actor,body==null?null:body.getRemarks(),null,null));}

    @PostMapping("/requests/{requestId}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable long requestId,HttpSession session){User actor=user(session);if(actor==null)return unauthorized();return ResponseEntity.ok(sharing.actOnRequest(requestId,"withdraw",actor,null,null,null));}

    @GetMapping("/agreements")
    public ResponseEntity<?> agreements(@RequestParam(defaultValue="false") boolean activeOnly,HttpSession session){boolean sys=systemAdmin(session);User actor=user(session);if(actor==null&&!sys)return unauthorized();return ResponseEntity.ok(sharing.agreements(actor,sys,activeOnly));}

    @GetMapping("/agreements/{agreementId}/grants")
    public ResponseEntity<?> grants(@PathVariable long agreementId,HttpSession session){boolean sys=systemAdmin(session);User actor=user(session);if(actor==null&&!sys)return unauthorized();return ResponseEntity.ok(sharing.agreementGrants(agreementId,actor,sys));}

    @PostMapping("/agreements/{agreementId}/revoke")
    public ResponseEntity<?> revokeAgreement(@PathVariable long agreementId,@RequestBody(required=false) SharingRequests.Revoke body,HttpSession session){boolean sys=systemAdmin(session);User actor=user(session);if(actor==null&&!sys)return unauthorized();sharing.revokeAgreement(agreementId,actor,body==null?null:body.getReason(),sys);return ResponseEntity.ok(Map.of("message","Agreement revoked."));}

    @PostMapping("/agreements/{agreementId}/grants/{grantId}/revoke")
    public ResponseEntity<?> revokeGrant(@PathVariable long agreementId,@PathVariable long grantId,@RequestBody(required=false) SharingRequests.Revoke body,HttpSession session){boolean sys=systemAdmin(session);User actor=user(session);if(actor==null&&!sys)return unauthorized();sharing.revokeGrant(agreementId,grantId,actor,body==null?null:body.getReason(),sys);return ResponseEntity.ok(Map.of("message","Access grant revoked."));}

    @GetMapping("/agreements/{agreementId}/fees")
    public ResponseEntity<?> fees(@PathVariable long agreementId,@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpSession session){boolean sys=systemAdmin(session);User actor=user(session);if(actor==null&&!sys)return unauthorized();return ResponseEntity.ok(sharing.usageFees(agreementId,actor,sys,date(from),date(to)));}

    @GetMapping("/equipment/{equipmentId}/schedule")
    public ResponseEntity<?> schedule(@PathVariable long equipmentId,@RequestParam String from,@RequestParam String to,HttpSession session){boolean sys=systemAdmin(session);User actor=user(session);if(actor==null&&!sys)return unauthorized();try{return ResponseEntity.ok(sharing.sharedSchedule(equipmentId,OffsetDateTime.parse(from),OffsetDateTime.parse(to),actor,sys));}catch(java.time.format.DateTimeParseException e){throw new BookingService.BookingException(400,"from and to must be ISO-8601 timestamps with a UTC offset.");}}

    private LocalDate date(String value){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(Exception e){throw new BookingService.BookingException(400,"Period dates must use YYYY-MM-DD.");}}
    private User user(HttpSession session){return session.getAttribute("authenticatedUser") instanceof User u?u:null;}
    private boolean systemAdmin(HttpSession session){return Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin"));}
    private ResponseEntity<Map<String,String>> unauthorized(){return ResponseEntity.status(401).body(Map.of("error","Sign in with an authorized account."));}
}
