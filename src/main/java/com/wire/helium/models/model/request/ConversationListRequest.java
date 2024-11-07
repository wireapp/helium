package com.wire.helium.models.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.xenon.backend.models.QualifiedId;

import java.util.List;

public class ConversationListRequest {
    @JsonProperty("qualified_ids")
    public List<QualifiedId> qualifiedIds;

    public ConversationListRequest(List<QualifiedId> qualifiedIds) {
        this.qualifiedIds = qualifiedIds;
    }
}
