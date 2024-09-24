//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.helium.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.xenon.models.otr.PreKey;

import java.util.List;
import java.util.Map;

public class NewClient {
    @JsonProperty("lastkey")
    public PreKey lastkey;

    @JsonProperty("prekeys")
    public List<PreKey> prekeys;

    @JsonProperty
    public String password;

    @JsonProperty("class")
    public String clazz;

    @JsonProperty
    public String type;

    @JsonProperty
    public String label;

    @JsonProperty("mls_public_keys")
    public Map<String, String> mlsPublicKeys;
}
