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
package uk.co.real_logic.artio.ilink;

import iLinkBinary.OrderMassActionReport562Decoder;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntArrayList;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.library.NotAppliedResponse;
import uk.co.real_logic.artio.messages.DisconnectReason;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static uk.co.real_logic.artio.ilink.ILink3TestServer.NO_AFFECTED_ORDERS_COUNT;

public class FakeILink3ConnectionHandler implements ILink3ConnectionHandler
{
    private final IntArrayList messageIds = new IntArrayList();
    private final List<Exception> exceptions = new ArrayList<>();
    private final Consumer<NotAppliedResponse> notAppliedResponse;

    private boolean hasReceivedNotApplied;
    private DisconnectReason disconnectReason;

    public FakeILink3ConnectionHandler(final Consumer<NotAppliedResponse> notAppliedResponse)
    {
        this.notAppliedResponse = notAppliedResponse;
    }

    public boolean hasReceivedNotApplied()
    {
        return hasReceivedNotApplied;
    }

    public void onBusinessMessage(
        final FixPConnection connection,
        final int templateId,
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final boolean possRetrans)
    {
        messageIds.add(templateId);

        if (templateId == OrderMassActionReport562Decoder.TEMPLATE_ID)
        {
            final OrderMassActionReport562Decoder decoder = new OrderMassActionReport562Decoder();
            decoder.wrap(buffer, offset, blockLength, version);
            assertEquals(NO_AFFECTED_ORDERS_COUNT, decoder.noAffectedOrders().count());
        }
    }

    public void onNotApplied(
        final FixPConnection connection,
        final long fromSequenceNumber,
        final long msgCount,
        final NotAppliedResponse response)
    {
        hasReceivedNotApplied = true;
        notAppliedResponse.accept(response);
    }

    public void onRetransmitReject(
        final FixPConnection connection,
        final String reason,
        final long requestTimestamp,
        final int errorCodes)
    {
    }

    public void onRetransmitTimeout(final FixPConnection connection)
    {
    }

    public void onSequence(final FixPConnection connection, final long nextSeqNo)
    {
    }

    public void onError(final FixPConnection connection, final Exception ex)
    {
        exceptions.add(ex);
    }

    public void onDisconnect(final FixPConnection connection, final DisconnectReason reason)
    {
        this.disconnectReason = reason;
    }

    public DisconnectReason disconnectReason()
    {
        return disconnectReason;
    }

    public IntArrayList messageIds()
    {
        return messageIds;
    }

    public List<Exception> exceptions()
    {
        return exceptions;
    }
}
