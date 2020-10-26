package com.wire.helium;

import com.wire.bots.cryptobox.CryptoException;
import com.wire.xenon.MessageHandlerBase;
import com.wire.xenon.MessageResourceBase;
import com.wire.xenon.backend.models.NewBot;
import com.wire.xenon.backend.models.Payload;
import com.wire.xenon.crypto.Crypto;
import com.wire.xenon.factories.CryptoFactory;
import com.wire.xenon.factories.StorageFactory;
import com.wire.xenon.state.State;
import com.wire.xenon.tools.Logger;

import javax.ws.rs.client.Client;
import java.io.IOException;
import java.util.UUID;

public class UserMessageResource extends MessageResourceBase {
    private UUID userId;
    private StorageFactory storageFactory;
    private CryptoFactory cryptoFactory;
    private Client client;
    private Crypto crypto;
    private State state;

    public UserMessageResource(MessageHandlerBase handler) {
        super(handler);
    }

    void onNewMessage(UUID eventId, UUID convId, Payload payload) throws Exception {
        if (convId == null) {
            Logger.warning("onNewMessage: %s convId is null", payload.type);
            return;
        }

        try {
            var client = getWireClient(convId);
            handleMessage(eventId, payload, client);
        } catch (CryptoException e) {
            Logger.error("onNewMessage: msg: %s, conv: %s, %s", eventId, convId, e);
        }
    }

    void onUpdate(UUID id, Payload payload) throws CryptoException, IOException {
        handleUpdate(id, payload, getWireClient(null));
    }

    WireClientImp getWireClient(UUID convId) throws CryptoException, IOException {
        var crypto = getCrypto();
        var newBot = getState();
        var api = new API(client, convId, newBot.token);
        return new WireClientImp(api, crypto, newBot, convId);
    }

    private NewBot getState() throws IOException {
        if (state == null)
            state = storageFactory.create(userId);
        return state.getState();
    }


    private Crypto getCrypto() throws CryptoException {
        if (crypto == null)
            crypto = cryptoFactory.create(userId);
        return crypto;
    }

    UserMessageResource addUserId(UUID userId) {
        this.userId = userId;
        return this;
    }

    UserMessageResource addStorageFactory(StorageFactory storageFactory) {
        this.storageFactory = storageFactory;
        return this;
    }

    UserMessageResource addCryptoFactory(CryptoFactory cryptoFactory) {
        this.cryptoFactory = cryptoFactory;
        return this;
    }

    UserMessageResource addClient(Client client) {
        this.client = client;
        return this;
    }

    protected void handleUpdate(UUID id, Payload payload, WireClientImp userClient) {
        switch (payload.type) {
            case "team.member-join":
                Logger.debug("%s: team: %s, user: %s", payload.type, payload.team, payload.data.user);
                handler.onNewTeamMember(userClient, payload.data.user);
                break;
            case "user.update":
                Logger.debug("%s: id: %s", payload.type, payload.user.id);
                handler.onUserUpdate(id, payload.user.id);
                break;
            default:
                Logger.debug("Unknown event: %s", payload.type);
                break;
        }
    }
}
