package com.wire.helium.helpers;

import com.wire.helium.API;
import com.wire.xenon.backend.models.QualifiedId;
import com.wire.xenon.models.otr.*;
import org.glassfish.jersey.client.JerseyClientBuilder;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DummyAPI extends API {
    private final Devices devices = new Devices();
    private final HashMap<String, PreKey> lastPreKeys = new HashMap<>(); // <userId-clientId, PreKey>
    private OtrMessage msg;

    public DummyAPI() {
        super(JerseyClientBuilder.createClient(), null, null);
    }

    @Override
    public Devices sendMessage(OtrMessage msg, boolean ignoreMissing) {
        this.msg = msg;
        Devices missing = new Devices();

        for (QualifiedId userId : devices.missing.toUserIds()) {
            for (String client : devices.missing.toClients(userId)) {
                if (msg.get(userId, client) == null)
                    missing.missing.add(userId, client);
            }
        }
        return missing;
    }

    @Override
    public PreKeys getPreKeys(Missing missing) {
        PreKeys ret = new PreKeys();
        for (QualifiedId userId : missing.toUserIds()) {
            HashMap<String, PreKey> devs = new HashMap<>();
            for (String client : missing.toClients(userId)) {
                String key = key(userId.id, client);
                PreKey preKey = lastPreKeys.get(key);
                devs.put(client, preKey);
            }
            final Map<UUID, Map<String, PreKey>> userMap = ret.qualifiedUserClientPrekeys.computeIfAbsent(userId.domain, k -> new HashMap<>());
            userMap.put(userId.id, devs);
        }

        return ret;
    }

    private PreKey convert(com.wire.bots.cryptobox.PreKey lastKey) {
        PreKey preKey = new PreKey();
        preKey.id = lastKey.id;
        preKey.key = Base64.getEncoder().encodeToString(lastKey.data);
        return preKey;
    }

    public void addDevice(QualifiedId userId, String client, com.wire.bots.cryptobox.PreKey lastKey) {
        devices.missing.add(userId, client);
        addLastKey(userId, client, lastKey);
    }

    private void addLastKey(QualifiedId userId, String clientId, com.wire.bots.cryptobox.PreKey lastKey) {
        String key = key(userId.id, clientId);
        PreKey preKey = convert(lastKey);
        lastPreKeys.put(key, preKey);
    }

    private String key(UUID userId, String clientId) {
        return String.format("%s-%s", userId, clientId);
    }

    public OtrMessage getMsg() {
        return msg;
    }
}
