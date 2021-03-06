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
package uk.co.real_logic.artio.binary_entrypoint;

import uk.co.real_logic.artio.engine.framer.FixPContexts;
import uk.co.real_logic.artio.fixp.FirstMessageRejectReason;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.messages.FixPProtocolType;

import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;

public class BinaryEntryPointContext implements FixPContext
{
    // persisted state
    private final long sessionID;
    private final long sessionVerID;
    private final long requestTimestampInNs;
    private final long enteringFirm;
    private boolean ended;

    // Not persisted
    private final boolean fromNegotiate;
    // Set when used in the engine but not library
    private FixPContexts contexts;

    private int offset = MISSING_INT;
    private BinaryEntryPointKey key;

    public BinaryEntryPointContext(
        final long sessionID,
        final long sessionVerID,
        final long timestampInNs,
        final long enteringFirm,
        final boolean fromNegotiate)
    {
        this.sessionID = sessionID;
        this.sessionVerID = sessionVerID;
        this.requestTimestampInNs = timestampInNs;
        this.enteringFirm = enteringFirm;
        this.fromNegotiate = fromNegotiate;

        ended = false;
        key = new BinaryEntryPointKey(sessionID);
    }

    public long sessionID()
    {
        return sessionID;
    }

    public long sessionVerID()
    {
        return sessionVerID;
    }

    public long requestTimestampInNs()
    {
        return requestTimestampInNs;
    }

    public long enteringFirm()
    {
        return enteringFirm;
    }

    public boolean fromNegotiate()
    {
        return fromNegotiate;
    }

    public String toString()
    {
        return "BinaryEntryPointContext{" +
            "sessionID=" + sessionID +
            ", sessionVerID=" + sessionVerID +
            ", requestTimestampInNs=" + requestTimestampInNs +
            ", enteringFirm=" + enteringFirm +
            ", fromNegotiate=" + fromNegotiate +
            '}';
    }

    public BinaryEntryPointKey toKey()
    {
        return key;
    }

    public FirstMessageRejectReason checkAccept(final FixPContext fixPContext)
    {
        if (fixPContext == null)
        {
            return checkFirstConnect();
        }

        // Sanity checks
        if (!(fixPContext instanceof BinaryEntryPointContext))
        {
            throw new IllegalArgumentException("Unable to compare protocol: " + this + " to " + fixPContext);
        }

        final BinaryEntryPointContext oldContext = (BinaryEntryPointContext)fixPContext;
        if (sessionID != oldContext.sessionID)
        {
            throw new IllegalArgumentException("Unable to compare: " + sessionID + " to " + oldContext.sessionID);
        }

        offset = oldContext.offset();
        contexts = oldContext.contexts;

        // negotiations should increment the session ver id
        if (fromNegotiate)
        {
            return sessionVerID > oldContext.sessionVerID ? null : FirstMessageRejectReason.NEGOTIATE_DUPLICATE_ID;
        }
        // establish messages shouldn't
        else
        {
            // Continue the same sequence
            if (oldContext.sessionVerID == sessionVerID)
            {
                // cannot re-restablish an ended session
                return oldContext.ended ? FirstMessageRejectReason.ESTABLISH_UNNEGOTIATED : null;
            }

            return FirstMessageRejectReason.ESTABLISH_UNNEGOTIATED;
        }
    }

    public void initiatorReconnect(final boolean reestablishConnection)
    {
        throw new UnsupportedOperationException();
    }

    public FixPProtocolType protocolType()
    {
        return FixPProtocolType.BINARY_ENTRYPOINT;
    }

    public void onEndSequence()
    {
        ended = true;
        contexts.updateContext(this);
    }

    public FirstMessageRejectReason checkFirstConnect()
    {
        if (!fromNegotiate)
        {
            return FirstMessageRejectReason.ESTABLISH_UNNEGOTIATED;
        }

        return null;
    }

    void offset(final int offset)
    {
        this.offset = offset;
    }

    int offset()
    {
        return offset;
    }

    void contexts(final FixPContexts contexts)
    {
        this.contexts = contexts;
    }

    boolean ended()
    {
        return ended;
    }

    void ended(final boolean ended)
    {
        this.ended = ended;
    }
}
