package com.wire.helium.models.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.xenon.backend.models.ClientUpdate;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicKeysResponse {

    @JsonProperty("removal")
    public ClientUpdate.MlsPublicKeys removal;
}
