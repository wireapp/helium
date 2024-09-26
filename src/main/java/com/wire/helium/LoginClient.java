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
import com.wire.helium.models.BackendConfiguration;
import com.wire.helium.models.NewClient;
import com.wire.xenon.Const;
import com.wire.xenon.exceptions.AuthException;
import com.wire.xenon.exceptions.HttpException;
import com.wire.xenon.models.otr.PreKey;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LoginClient {
    private static final String LABEL = "wbots";
    private static final String COOKIE_NAME = "zuid";
    protected static final String BACKEND_API_VERSION = "v6";
    protected final WebTarget apiVersionPath;
    protected final WebTarget clientsPath;
    private final WebTarget loginPath;
    private final WebTarget accessPath;
    private final WebTarget cookiesPath;

    public LoginClient(Client client) {
        WebTarget baseTarget = client.target(host());
        WebTarget versionedTarget = baseTarget.path(BACKEND_API_VERSION);
        apiVersionPath = baseTarget.path("api-version");
        loginPath = versionedTarget.path("login");
        clientsPath = versionedTarget.path("clients");
        accessPath = versionedTarget.path("access");
        cookiesPath = versionedTarget.path("cookies");
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public String host() {
        String host = System.getProperty(Const.WIRE_BOTS_SDK_API, System.getenv("WIRE_API_HOST"));
        return host != null ? host : "https://prod-nginz-https.wire.com";
    }

    public Access login(String email, String password) throws HttpException {
        return login(email, password, false);
    }

    /**
     * Gets basic info from the connected backend server, like the domain (e.g. wire.com)
     * and supported api versions.
     * NOTE: Domain is often necessary in federated environments and may be different than the Host header
     * coming from responses, making this request valuable.
     *
     * @return Deserialized response containing api versions and domain
     * @throws HttpException on connection issues, 4xx and 5xx are handled internally
     */
    public BackendConfiguration getBackendConfiguration() throws HttpException {
        Response response = apiVersionPath.request().get();

        int status = response.getStatus();

        if (status >= 400) {
            String entity = response.readEntity(String.class);
            throw new HttpException(entity, status);
        }

        return response.readEntity(BackendConfiguration.class);
    }

    public Access login(String email, String password, boolean persisted) throws HttpException {
        _Login login = new _Login();
        login.email = email;
        login.password = password;
        login.label = LABEL;

        Response response = loginPath.
                queryParam("persist", persisted).
                request(MediaType.APPLICATION_JSON).
                post(Entity.entity(login, MediaType.APPLICATION_JSON));

        int status = response.getStatus();

        if (status == 401) {   // Nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        }

        if (status == 403) {
            String entity = response.readEntity(String.class);
            throw new AuthException(entity, status);
        }

        if (status >= 400) {
            String entity = response.readEntity(String.class);
            throw new HttpException(entity, status);
        }

        Access access = response.readEntity(Access.class);

        NewCookie zuid = response.getCookies().get(COOKIE_NAME);
        if (zuid != null) {
            com.wire.helium.models.Cookie c = new com.wire.helium.models.Cookie();
            c.name = zuid.getName();
            c.value = zuid.getValue();
            access.setCookie(c);
        }
        return access;
    }

    public String registerClient(String token, String password, ArrayList<PreKey> preKeys,
                                 Map<String, String> mlsPublicKeys, PreKey lastKey) throws HttpException {
        String deviceClass = "tablet";
        String type = "permanent";
        return registerClient(token, password, preKeys, mlsPublicKeys, lastKey, deviceClass, type, LABEL);
    }

    /**
     * Registers a new client (device like phone or desktop) for an authenticated user.
     *
     * <p>
     *         Must pass Proteus OR MLS keys, not both. Keys must be generated beforehand with a given
     *         crypto session targeted for the requesting user.
     * </p>
     *
     * @param token authentication token from login/cookie
     * @param password Wire password
     * @param preKeys (Optional) List of Proteus prekeys to pass initially to the backend
     * @param mlsPublicKeys (Optional) List of MLS keys to pass initially to the backend
     * @param lastKey (Optional) Proteus backup key to pass initially to the backend
     * @param clazz    "tablet" | "phone" | "desktop"
     * @param type     "permanent" | "temporary"
     * @param label    can be anything
     * @return Client id
     */
    public String registerClient(String token, String password, ArrayList<PreKey> preKeys, Map<String, String> mlsPublicKeys,
                                 PreKey lastKey, String clazz, String type, String label) throws HttpException {
        NewClient newClient = new NewClient();
        newClient.password = password;
        newClient.lastkey = lastKey;
        newClient.prekeys = preKeys;
        newClient.mlsPublicKeys = mlsPublicKeys;
        newClient.clazz = clazz;
        newClient.label = label;
        newClient.type = type;

        Response response = clientsPath
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .post(Entity.entity(newClient, MediaType.APPLICATION_JSON));

        int status = response.getStatus();

        if (status == 401) {   // Nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }

        return response.readEntity(_Client.class).id;
    }

    public Access renewAccessToken(Cookie cookie) throws HttpException {
        Invocation.Builder builder = accessPath
                .request(MediaType.APPLICATION_JSON)
                .cookie(cookie);

        Response response = builder.
                post(Entity.entity(null, MediaType.APPLICATION_JSON));

        int status = response.getStatus();

        if (status == 401) {   // Nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status == 403) {
            throw response.readEntity(AuthException.class);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }

        Access access = response.readEntity(Access.class);

        NewCookie zuid = response.getCookies().get(COOKIE_NAME);
        if (zuid != null) {
            com.wire.helium.models.Cookie c = new com.wire.helium.models.Cookie();
            c.name = zuid.getName();
            c.value = zuid.getValue();
            access.setCookie(c);
        }
        return access;
    }

    public void logout(Cookie cookie, String token) throws HttpException {
        Response response = accessPath
                .path("logout")
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .cookie(cookie)
                .post(Entity.entity(null, MediaType.APPLICATION_JSON));

        int status = response.getStatus();
        if (status == 401) {   // Nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status == 403) {
            throw response.readEntity(AuthException.class);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }
    }

    public void removeCookies(String token, String password) throws HttpException {
        _RemoveCookies removeCookies = new _RemoveCookies();
        removeCookies.password = password;
        removeCookies.labels = Collections.singletonList(LABEL);

        Response response = cookiesPath.
                path("remove").
                request(MediaType.APPLICATION_JSON).
                header(HttpHeaders.AUTHORIZATION, bearer(token)).
                post(Entity.entity(removeCookies, MediaType.APPLICATION_JSON));

        int status = response.getStatus();

        if (status == 401) {   // Nginx returns text/html for 401. Cannot deserialize as json
            response.readEntity(String.class);
            throw new AuthException(status);
        } else if (status >= 400) {
            throw response.readEntity(HttpException.class);
        }

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
