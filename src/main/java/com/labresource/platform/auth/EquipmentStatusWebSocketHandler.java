package com.labresource.platform.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EquipmentStatusWebSocketHandler extends TextWebSocketHandler implements EquipmentStatusBroadcaster {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!Boolean.TRUE.equals(session.getAttributes().get("authenticated"))) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("An authenticated session is required."));
            return;
        }
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        sessions.remove(session);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void publish(long equipmentId) {
        TextMessage message = new TextMessage("{\"type\":\"equipment-status-changed\",\"equipmentId\":" + equipmentId + "}");
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) { sessions.remove(session); continue; }
            try { synchronized (session) { if (session.isOpen()) session.sendMessage(message); } }
            catch (IOException exception) { sessions.remove(session); try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { } }
        }
    }
}
