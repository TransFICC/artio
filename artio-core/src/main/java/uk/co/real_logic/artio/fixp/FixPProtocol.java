/*
 * Copyright 2021 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.fixp;

import io.aeron.ExclusivePublication;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.engine.framer.FixPContexts;
import uk.co.real_logic.artio.engine.logger.FixPSequenceNumberHandler;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.library.FixPSessionOwner;
import uk.co.real_logic.artio.library.InternalFixPConnection;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.protocol.GatewayPublication;

// Implementation classes should be stateless
public abstract class FixPProtocol
{
    private final FixPProtocolType protocolType;
    private final short encodingType;

    protected FixPProtocol(final FixPProtocolType protocolType, final short encodingType)
    {
        this.protocolType = protocolType;
        this.encodingType = encodingType;
    }

    public abstract AbstractFixPParser makeParser(FixPConnection session);

    public abstract AbstractFixPProxy makeProxy(
        ExclusivePublication publication, EpochNanoClock epochNanoClock);

    public abstract AbstractFixPOffsets makeOffsets();

    public FixPProtocolType protocolType()
    {
        return protocolType;
    }

    public abstract InternalFixPConnection makeAcceptorConnection(
        long connectionId,
        GatewayPublication outboundPublication,
        GatewayPublication inboundPublication,
        int libraryId,
        FixPSessionOwner libraryPoller,
        long lastReceivedSequenceNumber,
        long lastSentSequenceNumber,
        long lastConnectPayload,
        FixPContext context,
        CommonConfiguration configuration);

    public short encodingType()
    {
        return encodingType;
    }

    public abstract AbstractFixPStorage makeStorage(
        FixPContexts fixPContexts, EpochNanoClock epochNanoClock);

    public abstract AbstractFixPSequenceExtractor makeSequenceExtractor(
        FixPSequenceNumberHandler handler,
        SequenceNumberIndexReader sequenceNumberIndex);
}
