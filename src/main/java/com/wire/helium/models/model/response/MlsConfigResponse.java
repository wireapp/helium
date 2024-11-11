package com.wire.helium.models.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MlsConfigResponse {
    @JsonProperty
    public List<Integer> allowedCipherSuites;

    @JsonProperty
    public Integer defaultCipherSuite;

    @JsonProperty
    public String defaultProtocol;

    @JsonProperty
    public List<String> supportedProtocols;
}
