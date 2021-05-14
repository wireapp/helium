package com.wire.helium;

import com.wire.xenon.tools.Logger;
import jakarta.websocket.CloseReason;
import org.glassfish.tyrus.client.ClientManager;

public class SocketReconnectHandler extends ClientManager.ReconnectHandler {

    private final int delay;    // seconds

    public SocketReconnectHandler(int delay) {
        this.delay = delay;
    }

    @Override
    public boolean onDisconnect(CloseReason closeReason) {
        Logger.debug("Websocket onDisconnect: reason: %s", closeReason);
        return false;
    }

    @Override
    public boolean onConnectFailure(Exception e) {
        Logger.exception("Websocket onConnectFailure: reason: %s", e, e.getMessage());
        return true;
    }

    @Override
    public long getDelay() {
        return delay;
    }
}
