package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
public class BookingController {
    private final BookingService bookings;
    private final EquipmentUsageService usage;
    public BookingController(BookingService bookings,EquipmentUsageService usage) { this.bookings=bookings;this.usage=usage; }

    @PostMapping("/api/bookings")
    public ResponseEntity<?> create(@RequestBody(required=false) BookingRequests.Create body,HttpSession session){
        User user=currentUser(session); if(user==null)return unauthorized();
        return ResponseEntity.status(HttpStatus.CREATED).body(bookings.create(user,body));
    }
    @GetMapping("/api/bookings/my")
    public ResponseEntity<?> mine(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpSession session){User user=currentUser(session);return user==null?unauthorized():ResponseEntity.ok(bookings.mine(user,status,page,size));}
    @GetMapping("/api/bookings/{id}")
    public ResponseEntity<?> get(@PathVariable long id,HttpSession session){User user=currentUser(session);boolean manager=isManager(session);if(user==null&&!manager)return unauthorized();return ResponseEntity.ok(bookings.get(id,user,manager));}
    @PostMapping("/api/bookings/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long id,@RequestBody(required=false) BookingRequests.Reason body,HttpSession session){User user=currentUser(session);if(user==null)return unauthorized();return ResponseEntity.ok(bookings.cancel(id,user,false,body==null?null:body.reason()));}
    @PostMapping("/api/bookings/{id}/check-in")
    public ResponseEntity<?> checkIn(@PathVariable long id,HttpSession session){User user=currentUser(session);if(user==null)return unauthorized();return ResponseEntity.ok(usage.checkIn(id,user));}
    @PostMapping("/api/bookings/{id}/check-out")
    public ResponseEntity<?> checkOut(@PathVariable long id,@RequestBody(required=false) UsageRequests.Checkout body,HttpSession session){User user=currentUser(session);if(user==null)return unauthorized();return ResponseEntity.ok(usage.checkOut(id,user,body==null?null:body.remarks()));}
    @PostMapping("/api/bookings/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id,HttpSession session){if(!isManager(session))return isSignedIn(session)?forbidden():unauthorized();return ResponseEntity.ok(bookings.approve(id,currentUser(session),true));}
    @PostMapping("/api/bookings/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable long id,@RequestBody(required=false) BookingRequests.Reason body,HttpSession session){if(!isManager(session))return isSignedIn(session)?forbidden():unauthorized();return ResponseEntity.ok(bookings.reject(id,currentUser(session),true,body==null?null:body.reason()));}
    @GetMapping("/api/bookings/{id}/history")
    public ResponseEntity<?> history(@PathVariable long id,HttpSession session){User user=currentUser(session);boolean manager=isManager(session);if(user==null&&!manager)return unauthorized();return ResponseEntity.ok(bookings.history(id,user,manager));}
    @GetMapping("/api/equipment/{id}/availability")
    public ResponseEntity<?> availability(@PathVariable long id,@RequestParam String from,@RequestParam String to,HttpSession session){if(currentUser(session)==null&&!isManager(session))return unauthorized();try{return ResponseEntity.ok(bookings.availability(id,OffsetDateTime.parse(from),OffsetDateTime.parse(to)));}catch(java.time.format.DateTimeParseException e){throw new BookingService.BookingException(400,"from and to must be ISO-8601 timestamps with a UTC offset.");}}
    private User currentUser(HttpSession session){return session.getAttribute("authenticatedUser") instanceof User u?u:null;}
    private boolean isSignedIn(HttpSession session){return currentUser(session)!=null||isManager(session);}
    private boolean isManager(HttpSession session){return Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin"));}
    private ResponseEntity<Map<String,String>> unauthorized(){return ResponseEntity.status(401).body(Map.of("error","Sign in with an authorized account."));}
    private ResponseEntity<Map<String,String>> forbidden(){return ResponseEntity.status(403).body(Map.of("error","Manager access is required."));}
}
