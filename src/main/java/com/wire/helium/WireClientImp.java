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

package com.wire.helium;

import com.wire.xenon.WireAPI;
import com.wire.xenon.WireClientBase;
import com.wire.xenon.backend.models.NewBot;
import com.wire.xenon.crypto.Crypto;
import com.wire.xenon.exceptions.HttpException;

import java.util.UUID;

public class WireClientImp extends WireClientBase {
    private final UUID convId;

    public WireClientImp(WireAPI api, Crypto crypto, NewBot state, UUID convId) {
        super(api, crypto, state);
        this.convId = convId;
    }

    @Override
    public UUID getConversationId() {
        return convId;
    }

    public UUID getUserId(String username) throws HttpException {
        return api.getUserId(username);
    }
}
