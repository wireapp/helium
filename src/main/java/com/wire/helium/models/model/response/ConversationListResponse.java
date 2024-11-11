package com.wire.helium.models.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.backend.models.QualifiedId;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationListResponse {
    @JsonProperty("failed")
    public List<QualifiedId> failed;

    @JsonProperty("found")
    public List<Conversation> found;

    @JsonProperty("not_found")
    public List<QualifiedId> notFound;
}
