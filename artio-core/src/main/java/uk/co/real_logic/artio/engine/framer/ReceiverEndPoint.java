/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Objects;

import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.status.AtomicCounter;


import uk.co.real_logic.artio.Clock;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.decoder.AbstractLogonDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static java.nio.channels.SelectionKey.OP_READ;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE_TCP;
import static uk.co.real_logic.artio.dictionary.SessionConstants.LOGON_MESSAGE_TYPE;
import static uk.co.real_logic.artio.dictionary.SessionConstants.MIN_MESSAGE_SIZE;
import static uk.co.real_logic.artio.dictionary.SessionConstants.START_OF_HEADER;
import static uk.co.real_logic.artio.dictionary.SessionConstants.USER_REQUEST_MESSAGE_TYPE;
import static uk.co.real_logic.artio.messages.DisconnectReason.NO_LOGON;
import static uk.co.real_logic.artio.messages.DisconnectReason.REMOTE_DISCONNECT;
import static uk.co.real_logic.artio.messages.MessageStatus.INVALID;
import static uk.co.real_logic.artio.messages.MessageStatus.INVALID_BODYLENGTH;
import static uk.co.real_logic.artio.messages.MessageStatus.INVALID_CHECKSUM;
import static uk.co.real_logic.artio.messages.MessageStatus.OK;
import static uk.co.real_logic.artio.session.Session.UNKNOWN;
import static uk.co.real_logic.artio.util.AsciiBuffer.SEPARATOR;
import static uk.co.real_logic.artio.util.AsciiBuffer.UNKNOWN_INDEX;

/**
 * Handles incoming data from sockets.
 * <p>
 * The receiver end point frames the TCP FIX messages into Aeron fragments.
 * It also handles backpressure coming from the Aeron stream and applies it to
 * its own TCP connections.
 */
class ReceiverEndPoint
{
    private static final char INVALID_MESSAGE_TYPE = '-';

    private static final byte BODY_LENGTH_FIELD = 9;
    private static final byte BEGIN_STRING_FIELD = 8;

    private static final byte CHECKSUM0 = 1;
    private static final byte CHECKSUM1 = (byte)'1';
    private static final byte CHECKSUM2 = (byte)'0';
    private static final byte CHECKSUM3 = (byte)'=';

    private static final int MIN_CHECKSUM_SIZE = " 10=".length() + 1;
    private static final int CHECKSUM_TAG_SIZE = "10=".length();
    private static final int SOCKET_DISCONNECTED = -1;
    private static final int UNKNOWN_MESSAGE_TYPE = -1;
    private static final int BREAK = -1;

    private static final int UNKNOWN_INDEX_BACKPRESSURED = -2;

    private final AbstractLogonDecoder acceptorLogon;

    private final TcpChannel channel;
    private final GatewayPublication publication;
    private final long connectionId;
    private final SessionContexts sessionContexts;
    private final AtomicCounter messagesRead;
    private final Framer framer;
    private final ErrorHandler errorHandler;
    private final PasswordCleaner passwordCleaner = new PasswordCleaner();
    private final MutableAsciiBuffer buffer;
    private final ByteBuffer byteBuffer;
    private final GatewaySessions gatewaySessions;
    private final Clock clock;

    private int libraryId;
    private GatewaySession gatewaySession;
    private long sessionId;
    private int sequenceIndex;
    private int usedBufferData = 0;
    private boolean hasDisconnected = false;
    private SelectionKey selectionKey;
    private boolean isPaused = false;

    private AcceptorLogonResult pendingAcceptorLogon;
    private boolean hasNotifiedFramerOfLogonMessageReceived;
    private int pendingAcceptorLogonMsgOffset;
    private int pendingAcceptorLogonMsgLength;
    private long lastReadTimestamp;

    ReceiverEndPoint(
        final TcpChannel channel,
        final int bufferSize,
        final GatewayPublication publication,
        final long connectionId,
        final long sessionId,
        final int sequenceIndex,
        final SessionContexts sessionContexts,
        final AtomicCounter messagesRead,
        final Framer framer,
        final ErrorHandler errorHandler,
        final int libraryId,
        final GatewaySessions gatewaySessions,
        final Clock clock,
        final FixDictionary acceptorFixDictionary)
    {
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(sessionContexts, "sessionContexts");
        Objects.requireNonNull(gatewaySessions, "gatewaySessions");
        Objects.requireNonNull(clock, "clock");

        this.channel = channel;
        this.publication = publication;
        this.connectionId = connectionId;
        this.sessionId = sessionId;
        this.sequenceIndex = sequenceIndex;
        this.sessionContexts = sessionContexts;
        this.messagesRead = messagesRead;
        this.framer = framer;
        this.errorHandler = errorHandler;
        this.libraryId = libraryId;
        this.gatewaySessions = gatewaySessions;
        this.clock = clock;
        this.acceptorLogon = acceptorFixDictionary.makeLogonDecoder();

        byteBuffer = ByteBuffer.allocateDirect(bufferSize);
        buffer = new MutableAsciiBuffer(byteBuffer);
    }

    public long connectionId()
    {
        return connectionId;
    }

    int poll()
    {
        if (isPaused || hasDisconnected())
        {
            return 0;
        }

        if (pendingAcceptorLogon != null)
        {
            return pollPendingLogon();
        }

        try
        {
            final long latestReadTimestamp = clock.time();
            final int bytesRead = readData();
            if (frameMessages(bytesRead == 0 ? lastReadTimestamp : latestReadTimestamp))
            {
                lastReadTimestamp = latestReadTimestamp;
                return bytesRead;
            }
            else
            {
                lastReadTimestamp = latestReadTimestamp;
                return -bytesRead;
            }
        }
        catch (final ClosedChannelException ex)
        {
            onDisconnectDetected();
            return 1;
        }
        catch (final Exception ex)
        {
            // Regular disconnects aren't errors
            if (!Exceptions.isJustDisconnect(ex))
            {
                errorHandler.onError(ex);
            }

            onDisconnectDetected();
            return 1;
        }
    }

    private int pollPendingLogon()
    {
        // Retry-able under backpressure
        if (pendingAcceptorLogon.poll())
        {
            if (pendingAcceptorLogon.isAccepted())
            {
                if (!hasNotifiedFramerOfLogonMessageReceived)
                {
                    framer.onLogonMessageReceived(gatewaySession);
                    hasNotifiedFramerOfLogonMessageReceived = true;
                }

                return sendInitialLoginMessage();
            }
            else
            {
                completeDisconnect(pendingAcceptorLogon.reason());
            }
        }

        return 1;
    }

    private int sendInitialLoginMessage()
    {
        int offset = this.pendingAcceptorLogonMsgOffset;
        final int length = this.pendingAcceptorLogonMsgLength;

        // Might be paused at this point to ensure that a library has been notified of
        // the new session in soleLibraryMode
        if (isPaused)
        {
            moveRemainingDataToBufferStart(offset);
            return offset;
        }

        final long sessionId = gatewaySession.sessionId();
        final int sequenceIndex = gatewaySession.sequenceIndex();

        if (saveMessage(offset, LOGON_MESSAGE_TYPE, length, sessionId, sequenceIndex, lastReadTimestamp))
        {
            // Authentication is only complete (ie this state set) when the actual logon message has been saved.
            this.sessionId = sessionId;
            this.sequenceIndex = sequenceIndex;
            pendingAcceptorLogon = null;

            framer.receiverEndPointPollingOptional(connectionId);

            // Move any data received after the logon message.
            offset += length;
            moveRemainingDataToBufferStart(offset);
            return offset;
        }
        else
        {
            return offset;
        }
    }

    private int readData() throws IOException
    {
        final int dataRead = channel.read(byteBuffer);
        if (dataRead != SOCKET_DISCONNECTED)
        {
            if (dataRead > 0)
            {
                DebugLogger.log(FIX_MESSAGE_TCP, "Read     %s%n", buffer, 0, dataRead);
            }
            usedBufferData += dataRead;
        }
        else
        {
            onDisconnectDetected();
        }

        return dataRead;
    }

    boolean retryFrameMessages()
    {
        return frameMessages(lastReadTimestamp);
    }

    // true - no more framed messages in the buffer data to process. This could mean no more messages, or some data
    // that is an incomplete message.
    // false - needs to be retried, aka back-pressured
    private boolean frameMessages(final long readTimestamp)
    {
        int offset = 0;
        while (true)
        {
            if (usedBufferData < offset + SessionConstants.MIN_MESSAGE_SIZE) // Need more data
            {
                // Need more data
                break;
            }
            try
            {
                final int startOfBodyLength = scanForBodyLength(offset, readTimestamp);
                if (startOfBodyLength < 0)
                {
                    return startOfBodyLength == UNKNOWN_INDEX;
                }

                final int endOfBodyLength = scanEndOfBodyLength(startOfBodyLength);
                if (endOfBodyLength == UNKNOWN_INDEX) // Need more data
                {
                    break;
                }

                final int startOfChecksumTag = endOfBodyLength + getBodyLength(startOfBodyLength, endOfBodyLength);

                final int endOfChecksumTag = startOfChecksumTag + MIN_CHECKSUM_SIZE;
                if (endOfChecksumTag >= usedBufferData)
                {
                    break;
                }

                if (!validateBodyLength(startOfChecksumTag))
                {
                    final int endOfMessage = onInvalidBodyLength(offset, startOfChecksumTag, readTimestamp);
                    if (endOfMessage == BREAK)
                    {
                        break;
                    }
                    return true;
                }

                final int startOfChecksumValue = startOfChecksumTag + MIN_CHECKSUM_SIZE;
                final int endOfMessage = scanEndOfMessage(startOfChecksumValue);
                if (endOfMessage == UNKNOWN_INDEX)
                {
                    // Need more data
                    break;
                }

                final int messageType = getMessageType(endOfBodyLength, endOfMessage);
                final int length = (endOfMessage + 1) - offset;
                if (!validateChecksum(endOfMessage, startOfChecksumValue, offset, startOfChecksumTag))
                {
                    DebugLogger.log(FIX_MESSAGE, "Invalidated: %s%n", buffer, offset, length);

                    if (saveInvalidChecksumMessage(offset, messageType, length, readTimestamp))
                    {
                        return false;
                    }
                }
                else
                {
                    if (requiresAuthentication())
                    {
                        startAuthenticationFlow(offset, length, messageType);

                        // Actually has a logon message in it's buffer, but we return true because it's not
                        // a back-pressure scenario.
                        return true;
                    }

                    messagesRead.incrementOrdered();
                    if (!saveMessage(offset, messageType, length, readTimestamp))
                    {
                        return false;
                    }
                }

                offset += length;
            }
            catch (final IllegalArgumentException ex)
            {
                return !invalidateMessage(offset, readTimestamp);
            }
            catch (final Exception ex)
            {
                errorHandler.onError(ex);
                break;
            }
        }

        moveRemainingDataToBufferStart(offset);
        return true;
    }

    private int onInvalidBodyLength(final int offset, final int startOfChecksumTag, final long readTimestamp)
    {
        int checksumTagScanPoint = startOfChecksumTag + 1;
        while (!isStartOfChecksum(checksumTagScanPoint))
        {
            final int endOfScanPoint = checksumTagScanPoint + CHECKSUM_TAG_SIZE;
            if (endOfScanPoint >= usedBufferData)
            {
                return BREAK;
            }

            checksumTagScanPoint++;
        }

        final int endOfScanPoint = checksumTagScanPoint + CHECKSUM_TAG_SIZE;
        final int endOfMessage = buffer.scan(endOfScanPoint, usedBufferData, SEPARATOR) + 1;
        if (endOfMessage > usedBufferData)
        {
            return BREAK;
        }

        if (saveInvalidMessage(offset, endOfMessage, readTimestamp))
        {
            DebugLogger.log(FIX_MESSAGE, "Invalidated: %s%n", buffer, offset, endOfMessage - offset);
            return offset;
        }

        moveRemainingDataToBufferStart(endOfMessage);
        return offset;
    }

    boolean requiresAuthentication()
    {
        return sessionId == UNKNOWN;
    }

    private boolean validateChecksum(
        final int endOfMessage,
        final int startOfChecksumValue,
        final int offset,
        final int startOfChecksumTag)
    {
        final int expectedChecksum = buffer.getInt(startOfChecksumValue - 1, endOfMessage);
        final int computedChecksum = buffer.computeChecksum(offset, startOfChecksumTag + 1);
        return expectedChecksum == computedChecksum;
    }

    private int scanEndOfMessage(final int startOfChecksumValue)
    {
        return buffer.scan(startOfChecksumValue, usedBufferData - 1, START_OF_HEADER);
    }

    private int scanForBodyLength(final int offset, final long readTimestamp)
    {
        if (invalidTag(offset, BEGIN_STRING_FIELD))
        {
            return invalidateMessageUnknownIndex(offset, readTimestamp);
        }
        final int endOfCommonPrefix = scanNextField(offset + 2);
        if (endOfCommonPrefix == UNKNOWN_INDEX)
        {
            // no header end within MIN_MESSAGE_SIZE
            return invalidateMessageUnknownIndex(offset, readTimestamp);
        }
        final int startOfBodyTag = endOfCommonPrefix + 1;
        if (invalidTag(startOfBodyTag, BODY_LENGTH_FIELD))
        {
            return invalidateMessageUnknownIndex(offset, readTimestamp);
        }
        return startOfBodyTag + 2;
    }

    private int invalidateMessageUnknownIndex(final int offset, final long readTimestamp)
    {
        return invalidateMessage(offset, readTimestamp) ? UNKNOWN_INDEX_BACKPRESSURED : UNKNOWN_INDEX;
    }

    private int scanEndOfBodyLength(final int startOfBodyLength)
    {
        return buffer.scan(startOfBodyLength + 1, usedBufferData - 1, START_OF_HEADER);
    }

    private int scanNextField(final int startScan)
    {
        return buffer.scan(startScan + 1, usedBufferData - 1, START_OF_HEADER);
    }

    private void startAuthenticationFlow(final int offset, final int length, final int messageType)
    {
        if (sessionId != UNKNOWN)
        {
            return;
        }

        if (messageType == LOGON_MESSAGE_TYPE)
        {
            acceptorLogon.decode(buffer, offset, length);

            pendingAcceptorLogonMsgOffset = offset;
            pendingAcceptorLogonMsgLength = length;

            hasNotifiedFramerOfLogonMessageReceived = false;
            pendingAcceptorLogon = gatewaySessions.authenticate(
                acceptorLogon, connectionId(), gatewaySession, channel);
        }
        else
        {
            completeDisconnect(DisconnectReason.FIRST_MESSAGE_NOT_LOGON);
        }
    }

    // returns true if back-pressured
    private boolean stashIfBackPressured(final int offset, final long position)
    {
        final boolean backPressured = Pressure.isBackPressured(position);
        if (backPressured)
        {
            moveRemainingDataToBufferStart(offset);
        }

        return backPressured;
    }

    private boolean saveMessage(final int offset, final int messageType, final int length, final long readTimestamp)
    {
        return saveMessage(offset, messageType, length, sessionId, sequenceIndex, readTimestamp);
    }

    private boolean saveMessage(
        final int messageOffset,
        final int messageType,
        final int messageLength,
        final long sessionId,
        final int sequenceIndex,
        final long readTimestamp)
    {
        DirectBuffer buffer = this.buffer;
        int offset = messageOffset;
        int length = messageLength;

        final boolean isUserRequest = messageType == USER_REQUEST_MESSAGE_TYPE;
        if (messageType == LOGON_MESSAGE_TYPE || isUserRequest)
        {
            if (isUserRequest)
            {
                gatewaySessions.onUserRequest(
                    buffer, offset, length, gatewaySession.fixDictionary(), connectionId, sessionId);
            }

            passwordCleaner.clean(buffer, offset, length);

            offset = 0;
            buffer = passwordCleaner.cleanedBuffer();
            length = passwordCleaner.cleanedLength();
        }

        final long position = publication.saveMessage(
            buffer,
            offset,
            length,
            libraryId,
            messageType,
            sessionId,
            sequenceIndex,
            connectionId,
            OK,
            0,
            readTimestamp);

        if (Pressure.isBackPressured(position))
        {
            moveRemainingDataToBufferStart(offset);
            return false;
        }
        else
        {
            gatewaySession.onMessage(buffer, offset, length, messageType, sessionId);
            return true;
        }
    }

    private boolean validateBodyLength(final int startOfChecksumTag)
    {
        return isStartOfChecksum(startOfChecksumTag);
    }

    private boolean isStartOfChecksum(final int startOfChecksumTag)
    {
        return buffer.getByte(startOfChecksumTag) == CHECKSUM0 &&
            buffer.getByte(startOfChecksumTag + 1) == CHECKSUM1 &&
            buffer.getByte(startOfChecksumTag + 2) == CHECKSUM2 &&
            buffer.getByte(startOfChecksumTag + 3) == CHECKSUM3;
    }

    private int getMessageType(final int endOfBodyLength, final int indexOfLastByteOfMessage)
    {
        final int start = buffer.scan(endOfBodyLength, indexOfLastByteOfMessage, '=') + 1;
        final int end = buffer.scan(start, indexOfLastByteOfMessage, START_OF_HEADER);
        final int length = end - start;
        return buffer.getMessageType(start, length);
    }

    private int getBodyLength(final int startOfBodyLength, final int endOfBodyLength)
    {
        return buffer.getNatural(startOfBodyLength, endOfBodyLength);
    }

    private boolean invalidTag(final int startOfBodyTag, final byte tagId)
    {
        try
        {
            return buffer.getDigit(startOfBodyTag) != tagId ||
                buffer.getChar(startOfBodyTag + 1) != '=';
        }
        catch (final IllegalArgumentException ex)
        {
            return false;
        }
    }

    private void moveRemainingDataToBufferStart(final int offset)
    {
        usedBufferData -= offset;
        buffer.putBytes(0, buffer, offset, usedBufferData);
        // position set to ensure that back pressure is applied to TCP when read(byteBuffer) called.
        ByteBufferUtil.position(byteBuffer, usedBufferData);
    }

    // returns true if back-pressured
    private boolean invalidateMessage(final int offset, final long readTimestamp)
    {
        DebugLogger.log(FIX_MESSAGE, "Invalidated: %s%n", buffer, offset, MIN_MESSAGE_SIZE);
        return saveInvalidMessage(offset, readTimestamp);
    }

    private boolean saveInvalidMessage(final int offset, final int startOfChecksumTag, final long readTimestamp)
    {
        final long position = publication.saveMessage(
            buffer,
            offset,
            startOfChecksumTag,
            libraryId,
            UNKNOWN_MESSAGE_TYPE,
            sessionId,
            sequenceIndex,
            connectionId,
            INVALID_BODYLENGTH,
            0,
            readTimestamp);

        return stashIfBackPressured(offset, position);
    }

    // returns true if back-pressured
    private boolean saveInvalidMessage(final int offset, final long readTimestamp)
    {
        final long position = publication.saveMessage(
            buffer,
            offset,
            usedBufferData,
            libraryId,
            INVALID_MESSAGE_TYPE,
            sessionId,
            sequenceIndex,
            connectionId,
            INVALID,
            0,
            readTimestamp);

        final boolean backPressured = stashIfBackPressured(offset, position);

        if (!backPressured)
        {
            clearBuffer();
        }

        return backPressured;
    }

    private void clearBuffer()
    {
        moveRemainingDataToBufferStart(usedBufferData);
    }

    private boolean saveInvalidChecksumMessage(
        final int offset, final int messageType, final int length, final long readTimestamp)
    {
        final long position = publication.saveMessage(
            buffer,
            offset,
            length,
            libraryId,
            messageType,
            sessionId,
            sequenceIndex,
            connectionId,
            INVALID_CHECKSUM,
            0,
            readTimestamp);

        return stashIfBackPressured(offset, position);
    }

    public void close(final DisconnectReason reason)
    {
        closeResources();

        if (!hasDisconnected)
        {
            disconnectEndpoint(reason);
        }
    }

    private void closeResources()
    {
        try
        {
            channel.close();
            messagesRead.close();
        }
        catch (final Exception ex)
        {
            errorHandler.onError(ex);
        }
    }

    private void removeEndpointFromFramer()
    {
        framer.onDisconnect(libraryId, connectionId, null);
    }

    private void onDisconnectDetected()
    {
        completeDisconnect(REMOTE_DISCONNECT);
    }

    void onNoLogonDisconnect()
    {
        completeDisconnect(NO_LOGON);
    }

    private void completeDisconnect(final DisconnectReason reason)
    {
        disconnectEndpoint(reason);
        removeEndpointFromFramer();
    }

    private void disconnectEndpoint(final DisconnectReason reason)
    {
        framer.schedule(() -> publication.saveDisconnect(libraryId, connectionId, reason));

        sessionContexts.onDisconnect(sessionId);
        if (selectionKey != null)
        {
            selectionKey.cancel();
        }

        hasDisconnected = true;
    }

    boolean hasDisconnected()
    {
        return hasDisconnected;
    }

    public void register(final Selector selector) throws IOException
    {
        selectionKey = channel.register(selector, OP_READ, this);
    }

    public int libraryId()
    {
        return libraryId;
    }

    public void libraryId(final int libraryId)
    {
        this.libraryId = libraryId;
    }

    void gatewaySession(final GatewaySession gatewaySession)
    {
        this.gatewaySession = gatewaySession;
    }

    void pause()
    {
        isPaused = true;
    }

    void play()
    {
        isPaused = false;
    }

    public String toString()
    {
        return "ReceiverEndPoint: " + connectionId;
    }
}
