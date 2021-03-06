/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.CME_ENCODING_TYPE;

class ILink3ReceiverEndPoint extends FixPReceiverEndPoint
{
    private static final int NEGOTIATION_RESPONSE = 501;

    private final boolean isBackup;
    private final ILink3Context context;

    ILink3ReceiverEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final int bufferSize,
        final ErrorHandler errorHandler,
        final Framer framer,
        final GatewayPublication publication,
        final int libraryId,
        final boolean isBackup,
        final ILink3Context context,
        final EpochNanoClock epochNanoClock,
        final long correlationId)
    {
        super(
            connectionId,
            channel,
            bufferSize,
            errorHandler,
            framer,
            publication,
            libraryId,
            epochNanoClock,
            correlationId,
            CME_ENCODING_TYPE);
        this.isBackup = isBackup;
        this.context = context;
        sessionId(context.connectUuid());
    }

    void checkMessage(final MutableAsciiBuffer buffer, final int offset, final int messageSize)
    {
        if (readTemplateId(buffer, offset) == NEGOTIATION_RESPONSE)
        {
            context.confirmUuid();
        }
    }

    void trackDisconnect()
    {
        if (isBackup)
        {
            context.backupConnected(false);
        }
        else
        {
            context.primaryConnected(false);
        }
    }

    boolean requiresAuthentication()
    {
        return false;
    }
}
