package com.wire.helium.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BackendConfiguration {
    //Api version currently in dev stage and not production ready
    @JsonProperty
    @NotNull
    public Integer development;

    @JsonProperty
    @NotNull
    public String domain;

    @JsonProperty
    @NotNull
    public Boolean federation;

    // List of currently enabled api versions on this backend server
    @JsonProperty
    @NotNull
    public List<Integer> supported = new ArrayList<>();
}
