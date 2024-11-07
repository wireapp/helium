package com.wire.helium.models.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.backend.models.QualifiedId;

import java.util.List;

public class ConversationListResponse {
    @JsonProperty("failed")
    public List<QualifiedId> failed;

    // TODO(WPB-12040): possible issue with members returning as object(others[], self{}) and we expect a list?
    @JsonProperty("found")
    public List<Conversation> found;

    @JsonProperty("not_found")
    public List<QualifiedId> notFound;
}
