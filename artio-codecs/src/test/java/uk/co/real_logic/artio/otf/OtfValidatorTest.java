/*
 * Copyright 2015-2017 Real Logic Ltd.
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
package uk.co.real_logic.artio.otf;

import org.junit.Test;
import uk.co.real_logic.artio.dictionary.IntDictionary;
import uk.co.real_logic.artio.dictionary.generation.GenerationUtil;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.ValidationError.*;
import static uk.co.real_logic.artio.dictionary.SessionConstants.MESSAGE_TYPE;

public class OtfValidatorTest
{
    private static final int PACKED_HEARTBEAT = GenerationUtil.packMessageType("0");
    private OtfMessageAcceptor acceptor = mock(OtfMessageAcceptor.class);

    private IntDictionary requiredFields = new IntDictionary();
    private IntDictionary allFields = new IntDictionary();
    private MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[16 * 1024]);

    private OtfValidator validator = new OtfValidator(acceptor, allFields, requiredFields);

    @Test
    public void validStartMessageDelegates()
    {
        when:
        validator.onNext();

        then:
        verify(acceptor).onNext();
    }

    @Test
    public void validEndMessageDelegates()
    {
        given:
        heartBeatsAreKnownMessages();
        messageIsAHeartBeat();
        validateMessageType();

        when:
        validator.onComplete();

        then:
        verify(acceptor).onComplete();
    }

    @Test
    public void validMessageTypeDelegates()
    {
        given:
        heartBeatsAreKnownMessages();
        messageIsAHeartBeat();

        when:
        validateMessageType();

        then:
        verifyAcceptorReceivesMessageType();
    }

    @Test
    public void invalidMessageTypeNotifiesErrorHandler()
    {
        given:
        messageIsAHeartBeat();

        when:
        validateMessageType();

        then:
        verifyAcceptorNotNotifiedOf(MESSAGE_TYPE);
        verifyUnknownMessage();
    }

    @Test
    public void validFieldDelegates()
    {
        given:
        heartbeatsHaveATestReqId();
        messageIsAHeartBeat();

        when:
        validateMessageType();
        validateTestReqId();

        then:
        verifyAcceptorReceivesMessageType();
    }

    @Test
    public void unknownFieldNotifiesErrorHandler()
    {
        given:
        heartBeatsAreKnownMessages();
        messageIsAHeartBeat();

        when:
        validateMessageType();
        validateTestReqId();

        then:
        verifyAcceptorNotNotifiedOf(112);
        verifyUnknownField();
    }

    @Test
    public void missingRequiredFieldsNotifiesErrorHandler()
    {
        given:
        heartBeatsAreKnownMessages();
        testReqIdIsARequiredHeartBeatField();
        messageIsAHeartBeat();

        when:
        validateMessageType();
        validator.onComplete();

        then:
        verifyMissingRequiredField();
    }

    private void testReqIdIsARequiredHeartBeatField()
    {
        requiredFields.put(PACKED_HEARTBEAT, 112);
    }

    private void heartbeatsHaveATestReqId()
    {
        heartBeatsAreKnownMessages();
        allFields.put(PACKED_HEARTBEAT, 112);
    }

    private void heartBeatsAreKnownMessages()
    {
        requiredFields.put(PACKED_HEARTBEAT, MESSAGE_TYPE);
        allFields.put(PACKED_HEARTBEAT, MESSAGE_TYPE);
    }

    private void messageIsAHeartBeat()
    {
        buffer.putAscii(0, "0");
    }

    private void validateMessageType()
    {
        validator.onField(MESSAGE_TYPE, buffer, 0, 1);
    }

    private void verifyAcceptorReceivesMessageType()
    {
        verify(acceptor).onField(MESSAGE_TYPE, buffer, 0, 1);
    }

    private void verifyAcceptorNotNotifiedOf(final int tag)
    {
        verify(acceptor, never()).onField(tag, buffer, 0, 1);
    }

    private void verifyUnknownMessage()
    {
        verify(acceptor).onError(eq(UNKNOWN_MESSAGE_TYPE),
            eq(PACKED_HEARTBEAT), anyInt(), any(AsciiFieldFlyweight.class));
    }

    private void verifyUnknownField()
    {
        verify(acceptor).onError(eq(UNKNOWN_FIELD), eq(PACKED_HEARTBEAT),
            eq(112), any(AsciiFieldFlyweight.class));
    }

    private void verifyMissingRequiredField()
    {
        verify(acceptor).onError(eq(MISSING_REQUIRED_FIELD),
            eq(PACKED_HEARTBEAT), eq(112), any(AsciiFieldFlyweight.class));
    }

    private void validateTestReqId()
    {
        validator.onField(112, buffer, 0, 1);
    }
}
