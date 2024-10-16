//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.helium;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.protobuf.ByteString;
import com.wire.helium.models.Connection;
import com.wire.helium.models.NotificationList;
import com.wire.messages.Otr;
import com.wire.xenon.WireAPI;
import com.wire.xenon.assets.IAsset;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.backend.models.Member;
import com.wire.xenon.backend.models.QualifiedId;
import com.wire.xenon.backend.models.User;
import com.wire.xenon.exceptions.AuthException;
import com.wire.xenon.exceptions.HttpException;
import com.wire.xenon.models.AssetKey;
import com.wire.xenon.models.otr.*;
import com.wire.xenon.tools.Logger;
import com.wire.xenon.tools.Util;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class API extends LoginClient implements WireAPI {
    private final WebTarget conversationsPath;
    private final WebTarget usersPath;
    private final WebTarget assetsPath;
    private final WebTarget teamsPath;
    private final WebTarget connectionsPath;
    private final WebTarget selfPath;
    private final WebTarget notificationsPath;

    private final String token;
    private final QualifiedId convId;

    public API(Client client, QualifiedId convId, String token) {
        super(client);

        this.convId = convId;
        this.token = token;

        WebTarget versionedTarget = client.target(host()).path(BACKEND_API_VERSION);

        conversationsPath = versionedTarget.path("conversations");
        usersPath = versionedTarget.path("users");
        assetsPath = versionedTarget.path("assets");
        teamsPath = versionedTarget.path("teams");
        connectionsPath = versionedTarget.path("connections");
        selfPath = versionedTarget.path("self");
        notificationsPath = versionedTarget.path("notifications");
    }

    /**
     * Sends E2E encrypted messages to all clients already known, with opened cryptobox sessions.
     *
     * After sending those, the backend will return the list of clients that the service has no connection yet,
     * so prekeys for those specific clients can be downloaded a new cryptobox sessions initiated.
     * @param msg the message with the already encrypted clients
     * @param ignoreMissing when true, missing clients won't be blocking and just be returned,
     *                      when false, any missing recipient will block the message to be sent to anyone
     * @return devices that had issues or still need the message
     * @throws HttpException
     */
    @Override
    public Devices sendMessage(OtrMessage msg, boolean ignoreMissing) throws HttpException {
        final Otr.QualifiedNewOtrMessage.Builder protoMsgBuilder = createProtoMessageBuilder(msg);

        if (ignoreMissing) {
            protoMsgBuilder.setIgnoreAll(Otr.ClientMismatchStrategy.IgnoreAll.getDefaultInstance());
        } else {
            protoMsgBuilder.setReportAll(Otr.ClientMismatchStrategy.ReportAll.getDefaultInstance());
        }
        final Otr.QualifiedNewOtrMessage protoMsg = protoMsgBuilder.build();

        Response response = conversationsPath.
                path(convId.domain).
                path(convId.id.toString()).
                path("proteus/messages").
                queryParam("ignore_missing", ignoreMissing).
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(protoMsg, "application/x-protobuf"));

        int statusCode = response.getStatus();
        if (statusCode == 412) {
            return response.readEntity(Devices.class);
        } else if (statusCode >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("SendMessage http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        response.close();
        return new Devices();
    }

    /**
     * Sends E2E encrypted messages to all clients already known, with opened cryptobox sessions.
     *
     * After sending those, the backend will return the list of clients that the service has no connection yet,
     * so prekeys for those specific clients can be downloaded a new cryptobox sessions initiated.
     * @param msg the message with the already encrypted clients
     * @param userId If this users' client is missing, the message is not sent
     * @return devices that had issues or still need the message
     * @throws HttpException
     */
    @Override
    public Devices sendPartialMessage(OtrMessage msg, QualifiedId userId) throws HttpException {
        final Otr.QualifiedNewOtrMessage.Builder protoMsgBuilder = createProtoMessageBuilder(msg);
        final Otr.ClientMismatchStrategy.ReportOnly reportOnly = Otr.ClientMismatchStrategy.ReportOnly.newBuilder()
            .addAllUserIds(
                List.of(
                    Otr.QualifiedUserId.newBuilder()
                        .setDomain(userId.domain)
                        .setId(userId.id.toString())
                        .build()
                )
            )
            .build();
        protoMsgBuilder.setReportOnly(reportOnly);
        final Otr.QualifiedNewOtrMessage protoMsg = protoMsgBuilder.build();

        Response response = conversationsPath.
                path(convId.domain).
                path(convId.id.toString()).
                path("proteus/messages").
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(protoMsg, "application/x-protobuf"));

        int statusCode = response.getStatus();
        if (statusCode == 412) {
            return response.readEntity(Devices.class);
        } else if (statusCode >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("SendPartialMessage http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        response.close();
        return new Devices();
    }

    private Otr.QualifiedNewOtrMessage.Builder createProtoMessageBuilder(OtrMessage msg) {
        final Otr.QualifiedNewOtrMessage.Builder messageBuilder = Otr.QualifiedNewOtrMessage.newBuilder();

        final Otr.ClientId.Builder clientBuilder = Otr.ClientId.newBuilder();
        clientBuilder.setClient(Long.parseLong(msg.getSender(), 16));
        final Otr.ClientId clientId = clientBuilder.build();
        messageBuilder.setSender(clientId);

        Map<String, Map<UUID, ClientCipher>> domainBasedMap = recipientsToMap(msg.getRecipients());
        List<Otr.QualifiedUserEntry> protoRecipients = domainBasedMap.entrySet().stream()
            .map(it ->
                Otr.QualifiedUserEntry.newBuilder()
                    .setDomain(it.getKey())
                    .addAllEntries(it.getValue().entrySet().stream()
                        .map(u ->
                            Otr.UserEntry.newBuilder()
                                .setUser(Otr.UserId.newBuilder().setUuid(getProtoBytesFromUUID(u.getKey())))
                                .addAllClients(u.getValue().entrySet().stream()
                                    .map(c ->
                                        Otr.ClientEntry.newBuilder()
                                            .setClient(Otr.ClientId.newBuilder().setClient(Long.parseLong(c.getKey(), 16)).build())
                                            .setText(ByteString.copyFromUtf8(c.getValue()))
                                            .build()
                                    )
                                    .collect(Collectors.toList()))
                                .build()
                        )
                        .collect(Collectors.toList()))
                    .build()
            )
            .collect(Collectors.toList());
        messageBuilder.addAllRecipients(protoRecipients);

        return messageBuilder;
    }

    public static ByteString getProtoBytesFromUUID(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return ByteString.copyFrom(bb.array());
    }

    public Map<String, Map<UUID, ClientCipher>> recipientsToMap(Recipients recipients) {
        Map<String, Map<UUID, ClientCipher>> domainBasedMap = new HashMap<>();
        for (Map.Entry<QualifiedId, ClientCipher> recipient: recipients.entrySet()) {
            final Map<UUID, ClientCipher> uuidClientCipherMap = domainBasedMap.computeIfAbsent(recipient.getKey().domain, k -> new ConcurrentHashMap<>());
            uuidClientCipherMap.put(recipient.getKey().id, recipient.getValue());
        }
        return domainBasedMap;
    }

    @Override
    public PreKeys getPreKeys(Missing missing) {
        if (missing.isEmpty())
            return new PreKeys();

        Response response = usersPath.path("list-prekeys").
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                accept(MediaType.APPLICATION_JSON).
                post(Entity.entity(missing, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("GetPreKeys http error: %s, status: %d", msgError, response.getStatus());
            throw new RuntimeException(msgError);
        }
        return response.readEntity(PreKeys.class);
    }

    @Override
    public byte[] downloadAsset(String assetKey, String domain, String assetToken) throws HttpException {
        Invocation.Builder req = assetsPath
                .path(domain)
                .path(assetKey)
                .request()
                .header(HttpHeaders.AUTHORIZATION, bearer(token));

        if (assetToken != null)
            req.header("Asset-Token", assetToken);

        Response response = req.get();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("DownloadAsset http error %s, status: %d. AssetId: %s", msgError, response.getStatus(), assetKey);
            throw new HttpException(msgError, response.getStatus());
        }

        return response.readEntity(byte[].class);
    }

    @Override
    public void acceptConnection(QualifiedId user) throws HttpException {
        Connection connection = new Connection();
        connection.setStatus("accepted");

        Response response = connectionsPath.
                path(user.domain).
                path(user.id.toString()).
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                put(Entity.entity(connection, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("AcceptConnection http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }
        response.close();
    }

    @Override
    public AssetKey uploadAsset(IAsset asset) throws Exception {
        StringBuilder sb = new StringBuilder();

        // Part 1
        String strMetadata = String.format("{\"public\": %s, \"retention\": \"%s\"}",
                asset.isPublic(),
                asset.getRetention());
        sb.append("--frontier\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Content-Length: ")
                .append(strMetadata.length())
                .append("\r\n\r\n");
        sb.append(strMetadata)
                .append("\r\n");

        // Part 2
        sb.append("--frontier\r\n");
        sb.append("Content-Type: ")
                .append(asset.getMimeType())
                .append("\r\n");
        sb.append("Content-Length: ")
                .append(asset.getEncryptedData().length)
                .append("\r\n");
        sb.append("Content-MD5: ")
                .append(Util.calcMd5(asset.getEncryptedData()))
                .append("\r\n\r\n");

        // Complete
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.write(asset.getEncryptedData());
        os.write("\r\n--frontier--\r\n".getBytes(StandardCharsets.UTF_8));

        Response response = assetsPath
                .request(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .post(Entity.entity(os.toByteArray(), "multipart/mixed; boundary=frontier"));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("UploadAsset http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }
        return response.readEntity(AssetKey.class);
    }

    @Override
    public Conversation getConversation() {
        Response response = conversationsPath.
                path(convId.domain).
                path(convId.id.toString()).
                request().
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                get();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("GetConversation http error: %s, status: %d", msgError, response.getStatus());
            throw new RuntimeException(msgError);
        }

        _Conv conv = response.readEntity(_Conv.class);
        Conversation ret = new Conversation();
        ret.name = conv.name;
        ret.id = conv.id;
        ret.members = conv.members.others;
        return ret;
    }

    @Override
    public boolean deleteConversation(UUID teamId) throws HttpException {
        Response response = teamsPath.
                path(teamId.toString()).
                path("conversations").
                path(convId.id.toString()).
                request().
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                delete();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("DeleteConversation http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        return response.getStatus() == 200;
    }

    @Override
    public void addService(UUID serviceId, UUID providerId) throws HttpException {
        _Service service = new _Service();
        service.service = serviceId;
        service.provider = providerId;

        Response response = conversationsPath.
                path(convId.id.toString()).
                path("bots").
                request().
                accept(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(service, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("AddService http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }
    }

    @Override
    public void addParticipants(QualifiedId... userIds) throws HttpException {
        _NewConv newConv = new _NewConv();
        newConv.users = Arrays.asList(userIds);

        Response response = conversationsPath.
                path(convId.domain).
                path(convId.id.toString()).
                path("members").
                request().
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(newConv, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("AddParticipants http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }
    }

    @Override
    public Conversation createConversation(String name, UUID teamId, List<QualifiedId> users) throws HttpException {
        _NewConv newConv = new _NewConv();
        newConv.name = name;
        newConv.users = users;
        if (teamId != null) {
            newConv.team = new _TeamInfo();
            newConv.team.teamId = teamId;
        }

        Response response = conversationsPath.
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(newConv, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("CreateConversation http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        _Conv conv = response.readEntity(_Conv.class);

        Conversation ret = new Conversation();
        ret.name = conv.name;
        ret.id = conv.id;
        ret.members = conv.members.others;
        return ret;
    }

    @Override
    public Conversation createOne2One(UUID teamId, QualifiedId userId) throws HttpException {
        _NewConv newConv = new _NewConv();
        newConv.users = Collections.singletonList(userId);

        if (teamId != null) {
            newConv.team = new _TeamInfo();
            newConv.team.teamId = teamId;
        }

        Response response = conversationsPath
                .path("one2one")
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .post(Entity.entity(newConv, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("CreateOne2One http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        _Conv conv = response.readEntity(_Conv.class);

        Conversation ret = new Conversation();
        ret.name = conv.name;
        ret.id = conv.id;
        ret.members = conv.members.others;
        return ret;
    }

    @Override
    public void leaveConversation(QualifiedId user) throws HttpException {
        Response response = conversationsPath
                .path(convId.domain)
                .path(convId.id.toString())
                .path("members")
                .path(user.domain)
                .path(user.id.toString())
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .delete();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("LeaveConversation http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }
    }

    /**
     * Unused in base api, only needed for bot specific purposes which already extend this API.
     * @param preKeys list of pre-generated prekeys to upload to the backend
     */
    @Override
    public void uploadPreKeys(ArrayList<PreKey> preKeys) {
        throw new UnsupportedOperationException("Bot specific feature, use a more specific API implementation");
    }

    @Override
    public ArrayList<Integer> getAvailablePrekeys(String clientId) {
        Response response = clientsPath.
                path(clientId).
                path("prekeys").
                request().
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                accept(MediaType.APPLICATION_JSON).
                get();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("GetAvailablePrekeys http error: %s, status: %d", msgError, response.getStatus());
            throw new RuntimeException(msgError);
        }

        return response.readEntity(new GenericType<>() {});
    }

    /**
     * Unused in base api, only needed for bot specific purposes which already extend this API.
     * @param ids list of user ids
     */
    @Override
    public Collection<User> getUsers(Collection<QualifiedId> ids) {
        throw new UnsupportedOperationException("Bot specific feature, use a more specific API implementation");
    }

    /**
     * Get the metadata of a specific user based on its id.
     * @param userId qualified user id
     */
    @Override
    public User getUser(QualifiedId userId) throws HttpException {
        Response response = usersPath
            .path(userId.domain)
            .path(userId.id.toString())
            .request()
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .accept(MediaType.APPLICATION_JSON)
            .get();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("GetUser http error: %s, status: %d", msgError, response.getStatus());
            throw new RuntimeException(msgError);
        }
        return response.readEntity(User.class);
    }

    public NotificationList retrieveNotifications(String client, UUID since, int size) throws HttpException {
        WebTarget webTarget = notificationsPath
                .queryParam("client", client)
                .queryParam("size", size);

        if (since != null) {
            webTarget = webTarget
                    .queryParam("since", since.toString());
        }

        Response response = webTarget
                .request(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .get();

        int status = response.getStatus();

        if (status == 200) {
            return response.readEntity(NotificationList.class);
        } else if (status == 404) {
            final NotificationList emptyNotifications = new NotificationList();
            emptyNotifications.hasMore = false;
            emptyNotifications.notifications = new ArrayList<>();
            return emptyNotifications;
        } else if (status == 401) {   // Nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status == 403) {
            throw response.readEntity(AuthException.class);
        }

        throw response.readEntity(HttpException.class);
    }

    public boolean hasDevice(QualifiedId userId, String clientId) {
        Response response = usersPath.
                path(userId.domain).
                path(userId.id.toString()).
                path("clients").
                path(clientId).
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                get();

        response.close();
        return response.getStatus() == 200;
    }

    @Override
    public User getSelf() {
        Response response = selfPath.
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                get();

        if (response.getStatus() >= 400) {
            String msgError = response.readEntity(String.class);
            Logger.error("GetSelf http error: %s, status: %d", msgError, response.getStatus());
            throw new RuntimeException(msgError);
        }
        return response.readEntity(User.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class _Conv {
        @JsonProperty("qualified_conversation")
        public QualifiedId id;

        @JsonProperty
        public String name;

        @JsonProperty
        public _Members members;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class _Members {
        @JsonProperty
        public List<Member> others;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Service {
        public UUID service;
        public UUID provider;
    }

    static class _NewConv {
        @JsonProperty
        public String name;

        @JsonProperty
        public _TeamInfo team;

        @JsonProperty("qualified_users")
        public List<QualifiedId> users;

        @JsonProperty
        public _Service service;
    }

    static class _TeamInfo {
        @JsonProperty("teamid")
        public UUID teamId;

        @JsonProperty
        public boolean managed;
    }
}
