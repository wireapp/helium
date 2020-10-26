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
import com.wire.helium.models.Access;
import com.wire.helium.models.NewClient;
import com.wire.helium.models.NotificationList;
import com.wire.xenon.Const;
import com.wire.xenon.exceptions.AuthException;
import com.wire.xenon.exceptions.HttpException;
import com.wire.xenon.models.otr.PreKey;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.*;

public class LoginClient {
    private static final String LABEL = "wbots";
    private static final String COOKIE_NAME = "zuid";
    protected final WebTarget clientsPath;
    private final WebTarget loginPath;
    private final WebTarget accessPath;
    private final WebTarget cookiesPath;
    private final WebTarget notificationsPath;

    public LoginClient(Client client) {
        var host = host();
        loginPath = client
                .target(host)
                .path("login");
        clientsPath = client
                .target(host)
                .path("clients");
        accessPath = client
                .target(host)
                .path("access");

        cookiesPath = client
                .target(host)
                .path("cookies");

        notificationsPath = client
                .target(host)
                .path("notifications");
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public String host() {
        var host = System.getProperty(Const.WIRE_BOTS_SDK_API, System.getenv("WIRE_API_HOST"));
        return host != null ? host : "https://prod-nginz-https.wire.com";
    }

    public Access login(String email, String password) throws HttpException {
        return login(email, password, false);
    }

    public Access login(String email, String password, boolean persisted) throws HttpException {
        var login = new _Login();
        login.email = email;
        login.password = password;
        login.label = LABEL;

        var response = loginPath.
                queryParam("persist", persisted).
                request(MediaType.APPLICATION_JSON).
                post(Entity.entity(login, MediaType.APPLICATION_JSON));

        var status = response.getStatus();

        if (status == 401) {   //todo nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        }

        if (status == 403) {
            var entity = response.readEntity(String.class);
            throw new AuthException(entity, status);
        }

        if (status >= 400) {
            var entity = response.readEntity(String.class);
            throw new HttpException(entity, status);
        }

        var access = response.readEntity(Access.class);

        var zuid = response.getCookies().get(COOKIE_NAME);
        if (zuid != null) {
            var c = new com.wire.helium.models.Cookie();
            c.name = zuid.getName();
            c.value = zuid.getValue();
            access.setCookie(c);
        }
        return access;
    }

    @Deprecated
    public String registerClient(String token, String password, ArrayList<PreKey> preKeys, PreKey lastKey) throws HttpException {
        var deviceClass = "tablet";
        var type = "permanent";
        return registerClient(token, password, preKeys, lastKey, deviceClass, type, LABEL);
    }

    /**
     * @param password Wire password
     * @param clazz    "tablet" | "phone" | "desktop"
     * @param type     "permanent" | "temporary"
     * @param label    can be anything
     * @return Client id
     */
    public String registerClient(String token, String password, ArrayList<PreKey> preKeys, PreKey lastKey,
                                 String clazz, String type, String label) throws HttpException {
        var newClient = new NewClient();
        newClient.password = password;
        newClient.lastkey = lastKey;
        newClient.prekeys = preKeys;
        newClient.sigkeys.enckey = Base64.getEncoder().encodeToString(new byte[32]);
        newClient.sigkeys.mackey = Base64.getEncoder().encodeToString(new byte[32]);
        newClient.clazz = clazz;
        newClient.label = label;
        newClient.type = type;

        var response = clientsPath
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .post(Entity.entity(newClient, MediaType.APPLICATION_JSON));

        var status = response.getStatus();

        if (status == 401) {   //todo nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }

        return response.readEntity(_Client.class).id;
    }

    public Access renewAccessToken(Cookie cookie) throws HttpException {
        var builder = accessPath
                .request(MediaType.APPLICATION_JSON)
                .cookie(cookie);

        var response = builder.
                post(Entity.entity(null, MediaType.APPLICATION_JSON));

        var status = response.getStatus();

        if (status == 401) {   //todo nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status == 403) {
            throw response.readEntity(AuthException.class);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }

        var access = response.readEntity(Access.class);

        var zuid = response.getCookies().get(COOKIE_NAME);
        if (zuid != null) {
            var c = new com.wire.helium.models.Cookie();
            c.name = zuid.getName();
            c.value = zuid.getValue();
            access.setCookie(c);
        }
        return access;
    }

    public void logout(Cookie cookie, String token) throws HttpException {
        var response = accessPath
                .path("logout")
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .cookie(cookie)
                .post(Entity.entity(null, MediaType.APPLICATION_JSON));

        var status = response.getStatus();
        if (status == 401) {   //todo nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status == 403) {
            throw response.readEntity(AuthException.class);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }
    }

    public void removeCookies(String token, String password) throws HttpException {
        var removeCookies = new _RemoveCookies();
        removeCookies.password = password;
        removeCookies.labels = Collections.singletonList(LABEL);

        var response = cookiesPath.
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(removeCookies, MediaType.APPLICATION_JSON));

        var status = response.getStatus();

        if (status == 401) {   //todo nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }

    }

    public NotificationList retrieveNotifications(String client, UUID since, String token, int size) throws HttpException {
        var webTarget = notificationsPath
                .queryParam("client", client)
                .queryParam("size", size);

        if (since != null) {
            webTarget = webTarget
                    .queryParam("since", since.toString());
        }

        var response = webTarget
                .request(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .get();

        var status = response.getStatus();

        if (status == 200) {
            return response.readEntity(NotificationList.class);
        } else if (status == 404) {  //todo what???
            return response.readEntity(NotificationList.class);
        } else if (status == 401) {   //todo nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status == 403) {
            throw response.readEntity(AuthException.class);
        }

        throw response.readEntity(HttpException.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Login {
        public String email;
        public String password;
        public String label;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Client {
        public String id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _RemoveCookies {
        public String password;
        public List<String> labels;
    }
}
