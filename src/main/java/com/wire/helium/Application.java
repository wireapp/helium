package com.wire.helium;

import com.wire.bots.cryptobox.CryptoException;
import com.wire.helium.models.Event;
import com.wire.xenon.MessageHandlerBase;
import com.wire.xenon.backend.models.NewBot;
import com.wire.xenon.backend.models.Payload;
import com.wire.xenon.exceptions.HttpException;
import com.wire.xenon.factories.CryptoFactory;
import com.wire.xenon.factories.StorageFactory;
import com.wire.xenon.tools.Logger;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

import javax.websocket.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Cookie;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ClientEndpoint(decoders = EventDecoder.class)
public class Application {
    private static final int SIZE = 100;
    private final ScheduledExecutorService renewal;
    private final String email;
    private final String password;
    private final boolean sync;
    private final String wsUrl;

    private StorageFactory storageFactory;
    private CryptoFactory cryptoFactory;
    private Client client;
    private MessageHandlerBase handler;
    private UserMessageResource userMessageResource;
    private UUID userId;
    private Session session;
    private LoginClient loginClient;
    private Cookie cookie;

    public Application(String email, String password, boolean sync, String wsUrl) {
        this.email = email;
        this.password = password;
        this.sync = sync;
        this.wsUrl = wsUrl;

        renewal = Executors.newScheduledThreadPool(1);
    }

    public Application addHandler(MessageHandlerBase handler) {
        this.handler = handler;
        return this;
    }

    public Application addClient(Client client) {
        this.client = client;
        return this;
    }

    public Application addCryptoFactory(CryptoFactory cryptoFactory) {
        this.cryptoFactory = cryptoFactory;
        return this;
    }

    public Application addStorageFactory(StorageFactory storageFactory) {
        this.storageFactory = storageFactory;
        return this;
    }

    public void stop() throws Exception {
        Logger.info("Logging out...");
        var state = storageFactory.create(userId).getState();
        loginClient.logout(cookie, state.token);
    }

    public void start() throws Exception {
        loginClient = new LoginClient(client);
        var access = loginClient.login(email, password);

        userId = access.getUser();
        cookie = new Cookie(access.getCookie().name, access.getCookie().value);

        var clientId = getClientId();
        if (clientId == null) {
            clientId = newDevice(userId, password, access.getAccess_token());
            Logger.info("Created new device. clientId: %s", clientId);
        }
        var state = updateState(userId, clientId, access.getAccess_token(), null);

        Logger.info("Logged in as: %s, userId: %s, clientId: %s", email, state.id, state.client);

        var deviceId = state.client;
        renewal.scheduleAtFixedRate(() -> {
            try {
                var newAccess = loginClient.renewAccessToken(cookie);
                updateState(userId, deviceId, newAccess.getAccess_token(), null);
                Logger.info("Updated access token. Exp in: %d sec, cookie: %s",
                        newAccess.expires_in,
                        newAccess.getCookie() != null);
            } catch (Exception e) {
                Logger.warning("Token renewal error: %s", e);
            }
        }, 900, 900, TimeUnit.SECONDS);

        renewal.scheduleAtFixedRate(() -> {
            try {
                if (session != null) {
                    session.getBasicRemote().sendBinary(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                Logger.warning("Ping error: %s", e);
            }
        }, 10, 10, TimeUnit.SECONDS);

        userMessageResource = new UserMessageResource(handler)
                .addUserId(userId)
                .addClient(client)
                .addCryptoFactory(cryptoFactory)
                .addStorageFactory(storageFactory);

        // Pull from notification stream
        if (sync) {
            var notificationList = loginClient.retrieveNotifications(state.client,
                    since(state),
                    state.token,
                    SIZE);

            while (!notificationList.notifications.isEmpty()) {
                for (Event notification : notificationList.notifications) {
                    onMessage(notification);
                    state = updateState(userId, state.client, state.token, notification.id);
                }
                notificationList = loginClient.retrieveNotifications(state.client, since(state), state.token, SIZE);
            }
        }

        session = connectSocket();
        Logger.info("Websocket %s uri: %s", session.isOpen(), session.getRequestURI());
    }

    private UUID since(NewBot state) {
        return state.locale != null ? UUID.fromString(state.locale) : null;
    }

    @OnMessage
    public void onMessage(Event event) {
        if (event == null)
            return;

        for (Payload payload : event.payload) {
            try {
                switch (payload.type) {
                    case "team.member-join":
                    case "user.update":
                        userMessageResource.onUpdate(event.id, payload);
                        break;
                    case "user.connection":
                        userMessageResource.onNewMessage(
                                event.id,
                                /* payload.connection.from, */ //todo check this!!
                                payload.connection.convId,
                                payload);
                        break;
                    case "conversation.otr-message-add":
                    case "conversation.member-join":
                    case "conversation.member-leave":
                    case "conversation.create":
                        userMessageResource.onNewMessage(
                                event.id,
                                payload.convId,
                                payload);
                        break;
                    default:
                        Logger.info("Unknown type: %s, from: %s", payload.type, payload.from);
                }
            } catch (Exception e) {
                e.printStackTrace();
                Logger.error("Endpoint:onMessage: %s %s", payload.type, e);
            }
        }
    }

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        Logger.debug("Session opened: %s", session.getId());
    }

    @OnClose
    public void onClose(Session closed, CloseReason reason) throws IOException, DeploymentException {
        Logger.debug("Session closed: %s, %s", closed.getId(), reason);
        session = connectSocket();
    }

    private Session connectSocket() throws IOException, DeploymentException {
        var newBot = storageFactory
                .create(userId)
                .getState();

        var wss = client
                .target(wsUrl)
                .path("await")
                .queryParam("client", newBot.client)
                .queryParam("access_token", newBot.token)
                .getUri();

        // connect the Websocket
        var container = ClientManager.createClient();
        container.getProperties().put(ClientProperties.RECONNECT_HANDLER, new SocketReconnectHandler(5));
        container.setDefaultMaxSessionIdleTimeout(-1);

        return container.connectToServer(this, wss);
    }

    public String newDevice(UUID userId, String password, String token) throws CryptoException, HttpException {
        var crypto = cryptoFactory.create(userId);
        var loginClient = new LoginClient(client);

        var preKeys = crypto.newPreKeys(0, 20);
        var lastKey = crypto.newLastPreKey();

        return loginClient.registerClient(token, password, preKeys, lastKey, "tablet", "permanent", "lithium");
    }

    public String getClientId() {
        try {
            return storageFactory.create(userId).getState().client;
        } catch (IOException ex) {
            return null;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public WireClientImp getWireClient(UUID conversationId) throws CryptoException, IOException {
        return userMessageResource.getWireClient(conversationId);
    }

    public NewBot updateState(UUID userId, String clientId, String token, UUID last) throws IOException {
        var state = storageFactory.create(userId);

        NewBot newBot;
        try {
            newBot = state.getState();
        } catch (IOException ex) {
            newBot = new NewBot();
            newBot.id = userId;
            newBot.client = clientId;
        }

        newBot.token = token;
        if (last != null)
            newBot.locale = last.toString();

        state.saveState(newBot);
        return state.getState();
    }

}
