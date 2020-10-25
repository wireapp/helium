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
    public UUID user;

    @JsonProperty("access_token")
    public String access_token;

    @JsonProperty("expires_in")
    public int expires_in;

    @JsonProperty("token_type")
    public String token_type;

    public UUID getUser() {
        return user;
    }

    public String getAccess_token() {
        return access_token;
    }

    public Cookie getCookie() {
        return cookie;
    }

    @JsonIgnore
    public void setCookie(Cookie cookie) {
        this.cookie = cookie;
    }
}
