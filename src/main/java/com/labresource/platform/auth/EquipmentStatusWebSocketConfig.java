package com.labresource.platform.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

@Configuration
@EnableWebSocket
public class EquipmentStatusWebSocketConfig implements WebSocketConfigurer {
    private final EquipmentStatusWebSocketHandler handler;
    public EquipmentStatusWebSocketConfig(EquipmentStatusWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/equipment").addInterceptors(new SessionAuthHandshake());
    }

    private static class SessionAuthHandshake implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String,Object> attributes) {
            if (!(request instanceof ServletServerHttpRequest servletRequest)) return false;
            HttpSession session = servletRequest.getServletRequest().getSession(false);
            if (session == null) return false;
            boolean user = session.getAttribute("authenticatedUser") instanceof User;
            boolean admin = Boolean.TRUE.equals(session.getAttribute("authenticatedAdmin"));
            if (!user && !admin) return false;
            attributes.put("authenticated", true);
            attributes.put("admin", admin);
            return true;
        }
        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) { }
    }
}
