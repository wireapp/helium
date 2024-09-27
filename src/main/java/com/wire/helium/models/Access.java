package com.wire.helium.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Access {
    @JsonIgnore
    private Cookie cookie;

    @JsonProperty("user")
    public UUID userId; // Access and login endpoint do not yet return users' domain

    @JsonProperty("access_token")
    public String accessToken;

    @JsonProperty("expires_in")
    public int expiresIn; // Seconds

    @JsonProperty("token_type")
    public String tokenType;

    public UUID getUserId() {
        return userId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Cookie getCookie() {
        return cookie;
    }

    @JsonIgnore
    public void setCookie(Cookie cookie) {
        this.cookie = cookie;
    }

    @JsonIgnore
    public boolean hasCookie() {
        return cookie != null;
    }
}
