package com.wire.helium.models.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ConversationListPaginationConfig {
    @JsonProperty("paging_state")
    public String pagingState;

    @JsonProperty("size")
    public int size;

    public ConversationListPaginationConfig(String pagingState, int size) {
        this.pagingState = pagingState;
        this.size = size;
    }

    public String getPagingState() {
        return pagingState;
    }

    public void setPagingState(String pagingState) {
        this.pagingState = pagingState;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
