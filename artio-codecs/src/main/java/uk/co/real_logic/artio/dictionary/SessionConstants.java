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
package uk.co.real_logic.artio.dictionary;

import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.packMessageType;

public final class SessionConstants
{
    public static final byte START_OF_HEADER = 0x01;

    public static final int FIX4_HEADER_LENGTH = "8=FIX.4.2 ".length();
    public static final int FIXT_HEADER_LENGTH = "8=FIXT.1.1 ".length();

    // header and message length tag
    public static final int MIN_MESSAGE_SIZE = FIXT_HEADER_LENGTH + 6;

    // Message Types

    public static final int BODY_LENGTH = 9;
    public static final int CHECKSUM = 10;
    public static final int MSG_SEQ_NO = 34;
    public static final int MESSAGE_TYPE = 35;
    public static final int NEW_SEQ_NO = 36;
    public static final int POSS_DUP_FLAG = 43;
    public static final int SENDER_COMP_ID = 49;
    public static final int SENDING_TIME = 52;
    public static final int ORIG_SENDING_TIME = 122;
    public static final int TARGET_COMP_ID = 56;
    public static final int PASSWORD = 554;
    public static final int NEW_PASSWORD = 925;

    public static final String LOGON_MESSAGE_TYPE_STR = "A";
    public static final String HEARTBEAT_MESSAGE_TYPE_STR = "0";
    public static final String TEST_REQUEST_MESSAGE_TYPE_STR = "1";
    public static final String RESEND_REQUEST_MESSAGE_TYPE_STR = "2";
    public static final String REJECT_MESSAGE_TYPE_STR = "3";
    public static final String SEQUENCE_RESET_TYPE_STR = "4";
    public static final String LOGOUT_MESSAGE_TYPE_STR = "5";
    public static final String USER_REQUEST_MESSAGE_TYPE_STR = "BE";

    public static final int LOGON_MESSAGE_TYPE = packMessageType(LOGON_MESSAGE_TYPE_STR);
    public static final int HEARTBEAT_MESSAGE_TYPE = packMessageType(HEARTBEAT_MESSAGE_TYPE_STR);
    public static final int TEST_REQUEST_MESSAGE_TYPE = packMessageType(TEST_REQUEST_MESSAGE_TYPE_STR);
    public static final int RESEND_REQUEST_MESSAGE_TYPE = packMessageType(RESEND_REQUEST_MESSAGE_TYPE_STR);
    public static final int REJECT_MESSAGE_TYPE = packMessageType(REJECT_MESSAGE_TYPE_STR);
    public static final int SEQUENCE_RESET_MESSAGE_TYPE = packMessageType(SEQUENCE_RESET_TYPE_STR);
    public static final int LOGOUT_MESSAGE_TYPE = packMessageType(LOGOUT_MESSAGE_TYPE_STR);

    public static final int USER_REQUEST_MESSAGE_TYPE = packMessageType(USER_REQUEST_MESSAGE_TYPE_STR);

    public static final char[] LOGON_MESSAGE_TYPE_CHARS = LOGON_MESSAGE_TYPE_STR.toCharArray();
    public static final char[] HEARTBEAT_MESSAGE_TYPE_CHARS = HEARTBEAT_MESSAGE_TYPE_STR.toCharArray();
    public static final char[] TEST_REQUEST_MESSAGE_TYPE_CHARS = TEST_REQUEST_MESSAGE_TYPE_STR.toCharArray();
    public static final char[] REJECT_MESSAGE_TYPE_CHARS = REJECT_MESSAGE_TYPE_STR.toCharArray();
    public static final char[] SEQUENCE_RESET_MESSAGE_TYPE_CHARS = SEQUENCE_RESET_TYPE_STR.toCharArray();

    public static final int INCORRECT_DATA_FORMAT_FOR_VALUE = 6;

}
