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
import com.wire.helium.models.model.response.FeatureConfig;
import com.wire.helium.models.NotificationList;
import com.wire.helium.models.model.response.PublicKeysResponse;
import com.wire.helium.models.model.request.ConversationListPaginationConfig;
import com.wire.helium.models.model.request.ConversationListRequest;
import com.wire.helium.models.model.response.ConversationListIdsResponse;
import com.wire.helium.models.model.response.ConversationListResponse;
import com.wire.messages.Otr;
import com.wire.xenon.WireAPI;
import com.wire.xenon.assets.IAsset;
import com.wire.xenon.backend.KeyPackageUpdate;
import com.wire.xenon.backend.models.*;
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
    private final WebTarget versionedPath;
    private final WebTarget conversationsPath;
    private final WebTarget usersPath;
    private final WebTarget assetsPath;
    private final WebTarget teamsPath;
    private final WebTarget connectionsPath;
    private final WebTarget selfPath;
    private final WebTarget notificationsPath;
    private final WebTarget clientsPath;
    private final WebTarget mlsPath;

    private final String token;
    private final QualifiedId convId;

    public API(Client client, QualifiedId convId, String token) {
        super(client);

        this.convId = convId;
        this.token = token;

        WebTarget versionedTarget = client.target(host()).path(BACKEND_API_VERSION);
        versionedPath = versionedTarget;

        conversationsPath = versionedTarget.path("conversations");
        usersPath = versionedTarget.path("users");
        assetsPath = versionedTarget.path("assets");
        teamsPath = versionedTarget.path("teams");
        connectionsPath = versionedTarget.path("connections");
        selfPath = versionedTarget.path("self");
        notificationsPath = versionedTarget.path("notifications");
        clientsPath = versionedTarget.path("clients");
        mlsPath = versionedTarget.path("mls");
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

        return response.readEntity(Conversation.class);
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

        if (isErrorResponse(response.getStatus())) {
            String msgError = response.readEntity(String.class);
            Logger.error("DeleteConversation http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        response.close();
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

        if (isErrorResponse(response.getStatus())) {
            String msgError = response.readEntity(String.class);
            Logger.error("AddService http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        response.close();
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

        if (isErrorResponse(response.getStatus())) {
            String msgError = response.readEntity(String.class);
            Logger.error("AddParticipants http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        response.close();
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

        return response.readEntity(Conversation.class);
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

        return response.readEntity(Conversation.class);
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

        if (isErrorResponse(response.getStatus())) {
            String msgError = response.readEntity(String.class);
            Logger.error("LeaveConversation http error: %s, status: %d", msgError, response.getStatus());
            throw new HttpException(msgError, response.getStatus());
        }

        response.close();
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
            response.close();
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

    /**
     * <p>
     *     To verify if MLS is enabled we need to go through 2 requests. They are:
     * </p>
     * <p>
     *     First: from GET /feature-configs there will be a `mls` object containing a `status` of type boolean
     * </p>
     * <p>
     *     Second: from GET /mls/public/keys returning a `removal` object containing public keys
     * </p>
     * <p>
     *     If the first value is false, then we already return a `false` value.
     *     If the first value is true, then we do the second request, in case it returns a 200 HTTP Code then MLS is
     *     enabled and we can return a `true` value.
     *     <br />
     *     In case any of those requests fail (with HTTP Code >= 400) then we assume it is not enabled and log the error.
     * </p>
     *
     * @return boolean
     */
    @Override
    public boolean isMlsEnabled() {
        Response featureConfigsResponse = versionedPath
            .path("feature-configs")
            .request(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .get();

        if (isErrorResponse(featureConfigsResponse.getStatus())) {
            String msgError = featureConfigsResponse.readEntity(String.class);
            Logger.error("isMlsEnabled - Feature Configs error: %s, status: %d", msgError, featureConfigsResponse.getStatus());
            return false;
        }

        FeatureConfig featureConfig = featureConfigsResponse.readEntity(FeatureConfig.class);

        if (featureConfig.mls.isMlsStatusEnabled()) {
            Response mlsPublicKeysResponse = mlsPath
                .path("public-keys")
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .get();

            if (isErrorResponse(mlsPublicKeysResponse.getStatus())) {
                String msgError = mlsPublicKeysResponse.readEntity(String.class);
                Logger.error("isMlsEnabled - Public Keys error: %s, status: %d", msgError, mlsPublicKeysResponse.getStatus());
                return false;
            }

            try {
                PublicKeysResponse publicKeysResponse = mlsPublicKeysResponse.readEntity(PublicKeysResponse.class);
            } catch (Exception e) {
                Logger.error("isMlsEnabled - Public Keys Deserialization error: %s", e.getMessage());
                return false;
            }

            return true;
        }

        return false;
    }

    /**
     * <p>
     *     To upload client public key we PUT a {@link ClientUpdate} object containing the public keys
     *     to /clients/{clientId}
     * </p>
     * <p>
     *     As there is no return, in case it fails we just map the HTTP Code and log the message.
     * </p>
     *
     * @param clientId      clientId to upload the public keys
     * @param clientUpdate  the public keys
     * @throws RuntimeException if the request fails
     */
    @Override
    public void uploadClientPublicKey(String clientId, ClientUpdate clientUpdate) throws RuntimeException {
        Response response = clientsPath
            .path(clientId)
            .request(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .put(Entity.json(clientUpdate));

        if (isErrorResponse(response.getStatus())) {
            String errorResponse = response.readEntity(String.class);
            String errorMessage = String.format(
                "uploadClientPublicKey error: %s, clientId: %s, status: %d",
                errorResponse, clientId, response.getStatus()
            );

            Logger.error(errorMessage);
            throw new RuntimeException(errorResponse);
        }

        Logger.info("uploadClientPublicKey success for clientId: %s", clientId);
        response.close();
    }

    /**
     * <p>
     *     To upload client key packages we POST a {@link KeyPackageUpdate} object containing a list of package keys
     *     to /mls/key-packages/self/{clientId}
     * </p>
     * <p>
     *     As there is no return, in case it fails we just map the HTTP Code and log the message.
     * </p>
     *
     * @param clientId          clientId to upload the package keys
     * @param keyPackageUpdate  list of package keys
     * @throws RuntimeException if the request fails
     */
    @Override
    public void uploadClientKeyPackages(String clientId, KeyPackageUpdate keyPackageUpdate) throws RuntimeException {
        Response response = mlsPath
            .path("key-packages")
            .path("self")
            .path(clientId)
            .request(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .post(Entity.json(keyPackageUpdate));

        if (isErrorResponse(response.getStatus())) {
            String errorResponse = response.readEntity(String.class);
            String errorMessage = String.format(
                "uploadClientKeyPackages error: %s, clientId: %s, status: %d",
                errorResponse, clientId, response.getStatus()
            );

            Logger.error(errorMessage);
            throw new RuntimeException(errorResponse);
        }

        Logger.info("uploadClientKeyPackages success for clientId: %s", clientId);
        response.close();
    }

    @Override
    public byte[] getConversationGroupInfo(QualifiedId conversationId) throws RuntimeException {
        Response response = conversationsPath
            .path(conversationId.domain)
            .path(conversationId.id.toString())
            .path("groupinfo")
            .request(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .accept("message/mls")
            .get();

        if (isSuccessResponse(response.getStatus())) {
            return response.readEntity(byte[].class);
        }

        String errorResponse = response.readEntity(String.class);
        if (isErrorResponse(response.getStatus())) {
            Logger.error("getConversationGroupInfo error: %s, status: %d", errorResponse, response.getStatus());
        }

        throw new RuntimeException(errorResponse);
    }

    @Override
    public void commitMlsBundle(byte[] commitBundle) {
        Response response = mlsPath
            .path("commit-bundles")
            .request(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .post(Entity.entity(commitBundle, "message/mls"));

        if (isSuccessResponse(response.getStatus())) {
            Logger.info("commitMlsBundle success.");
            response.close();
            return;
        }

        String errorResponse = response.readEntity(String.class);
        if (isErrorResponse(response.getStatus())) {
            Logger.error("commitMlsBundle error: %s, status: %d", errorResponse, response.getStatus());
        }

        throw new RuntimeException(errorResponse);
    }

    /**
     * <p>
     *     In order to get user conversations, first we need to get all the paginated conversation ids.
     * </p>
     * <p>
     *     For getting the conversation details, we need to do a "paginated" request, as the backend has a limit of
     *     1000 conversation ids per request.
     * </p>
     *
     * @return List of {@link Conversation} details from the fetched conversation ids.
     */
    @Override
    public List<Conversation> getUserConversations() {
        ConversationListPaginationConfig pagingConfig = new ConversationListPaginationConfig(
            null,
            100
        );

        List<QualifiedId> conversationIds = new ArrayList<>();
        List<Conversation> conversations = new ArrayList<>();

        boolean hasMorePages;
        do {
            hasMorePages = false;

            Response listIdsResponse = conversationsPath
                .path("list-ids")
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .post(Entity.entity(pagingConfig, MediaType.APPLICATION_JSON));

            if (isErrorResponse(listIdsResponse.getStatus())) {
                String msgError = listIdsResponse.readEntity(String.class);
                Logger.error("getUserConversations - List Ids error: %s, status: %d", msgError, listIdsResponse.getStatus());
            }

            if (isSuccessResponse(listIdsResponse.getStatus())) {
                ConversationListIdsResponse conversationListIds = listIdsResponse.readEntity(ConversationListIdsResponse.class);
                hasMorePages = conversationListIds.hasMore;
                pagingConfig.setPagingState(conversationListIds.pagingState);

                conversationIds.addAll(conversationListIds.qualifiedConversations);

                Logger.info("getUserConversations - List Ids success. has more pages: " + hasMorePages);
            }
        } while (hasMorePages);

        if (!conversationIds.isEmpty()) {
            int startIndex = 0;
            int endIndex = 1000;
            do {
                if (endIndex > conversationIds.size()) {
                    endIndex = conversationIds.size();
                }

                conversations.addAll(getConversationsFromIds(conversationIds.subList(startIndex, endIndex)));
                startIndex += 1000;
                endIndex += 1000;
            } while (endIndex < conversationIds.size() + 1000);
        }

        return conversations;
    }

    private List<Conversation> getConversationsFromIds(List<QualifiedId> conversationIds) {
        Response conversationListResponse = conversationsPath
            .path("/list")
            .request(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .post(Entity.entity(
                new ConversationListRequest(conversationIds),
                MediaType.APPLICATION_JSON
            ));

        if (conversationListResponse.getStatus() == 200) {
            ConversationListResponse result = conversationListResponse.readEntity(ConversationListResponse.class);

            return result.found;
        }

        if (conversationListResponse.getStatus() >= 400) {
            String msgError = conversationListResponse.readEntity(String.class);
            Logger.error("getUserConversations - Conversation List error: %s, status: %d", msgError, conversationListResponse.getStatus());
        }

        return List.of();
    }

    private boolean isErrorResponse(int statusCode) {
        return Response.Status.Family.familyOf(statusCode).equals(Response.Status.Family.CLIENT_ERROR)
            || Response.Status.Family.familyOf(statusCode).equals(Response.Status.Family.SERVER_ERROR);
    }

    private boolean isSuccessResponse(int statusCode) {
        return Response.Status.Family.familyOf(statusCode).equals(Response.Status.Family.SUCCESSFUL);
    }

    /**
     * @deprecated This class is deprecated and in case there is any work related to _Service,
     * {@link Service} can be used instead.
     */
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
