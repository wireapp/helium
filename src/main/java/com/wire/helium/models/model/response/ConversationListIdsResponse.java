package com.wire.helium.models.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.xenon.backend.models.QualifiedId;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationListIdsResponse {
    @JsonProperty("has_more")
    public boolean hasMore;

    @JsonProperty("paging_state")
    public String pagingState;

    @JsonProperty("qualified_conversations")
    public List<QualifiedId> qualifiedConversations;
}
