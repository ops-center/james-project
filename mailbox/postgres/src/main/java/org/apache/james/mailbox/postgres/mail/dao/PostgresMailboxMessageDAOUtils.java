/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.postgres.mail.dao;

import static org.apache.james.backends.postgres.PostgresCommons.LOCAL_DATE_TIME_DATE_FUNCTION;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_DESCRIPTION;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_DISPOSITION_PARAMETERS;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_DISPOSITION_TYPE;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_LANGUAGE;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_LOCATION;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_MD5;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_TRANSFER_ENCODING;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.CONTENT_TYPE_PARAMETERS;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.INTERNAL_DATE;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageTable.SIZE;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.IS_ANSWERED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.IS_DELETED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.IS_DRAFT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.IS_FLAGGED;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.IS_RECENT;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.IS_SEEN;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.MAILBOX_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.MESSAGE_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.MESSAGE_UID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.MOD_SEQ;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.SAVE_DATE;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.THREAD_ID;
import static org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition.MessageToMailboxTable.USER_FLAGS;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import jakarta.mail.Flags;

import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.postgres.PostgresMailboxId;
import org.apache.james.mailbox.postgres.PostgresMessageId;
import org.apache.james.mailbox.postgres.mail.PostgresMessageDataDefinition;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.model.impl.Properties;
import org.apache.james.mailbox.store.mail.model.impl.PropertyBuilder;
import org.jooq.Field;
import org.jooq.Record;

interface PostgresMailboxMessageDAOUtils {
    Map<Field<Boolean>, Flags.Flag> BOOLEAN_FLAGS_MAPPING = Map.of(
        IS_ANSWERED, Flags.Flag.ANSWERED,
        IS_DELETED, Flags.Flag.DELETED,
        IS_DRAFT, Flags.Flag.DRAFT,
        IS_FLAGGED, Flags.Flag.FLAGGED,
        IS_RECENT, Flags.Flag.RECENT,
        IS_SEEN, Flags.Flag.SEEN);
    Function<Record, MessageUid> RECORD_TO_MESSAGE_UID_FUNCTION = record -> MessageUid.of(record.get(MESSAGE_UID));
    Function<Record, Flags> RECORD_TO_FLAGS_FUNCTION = record -> {
        Flags flags = new Flags();
        BOOLEAN_FLAGS_MAPPING.forEach((flagColumn, flagMapped) -> {
            if (record.get(flagColumn)) {
                flags.add(flagMapped);
            }
        });

        Optional.ofNullable(record.get(USER_FLAGS)).stream()
            .flatMap(Arrays::stream)
            .forEach(flags::add);
        return flags;
    };

    Function<Record, ThreadId> RECORD_TO_THREAD_ID_FUNCTION = record -> Optional.ofNullable(record.get(THREAD_ID))
        .map(threadIdAsUuid -> ThreadId.fromBaseMessageId(PostgresMessageId.Factory.of(threadIdAsUuid)))
        .orElse(ThreadId.fromBaseMessageId(PostgresMessageId.Factory.of(record.get(MESSAGE_ID))));


    Field<?>[] MESSAGE_METADATA_FIELDS_REQUIRE = new Field[]{
        MESSAGE_UID,
        MOD_SEQ,
        SIZE,
        INTERNAL_DATE,
        SAVE_DATE,
        MESSAGE_ID,
        THREAD_ID,
        IS_ANSWERED,
        IS_DELETED,
        IS_DRAFT,
        IS_FLAGGED,
        IS_RECENT,
        IS_SEEN,
        USER_FLAGS
    };

    Function<Record, MessageMetaData> RECORD_TO_MESSAGE_METADATA_FUNCTION = record ->
        new MessageMetaData(MessageUid.of(record.get(MESSAGE_UID)),
            ModSeq.of(record.get(MOD_SEQ)),
            RECORD_TO_FLAGS_FUNCTION.apply(record),
            record.get(SIZE),
            LOCAL_DATE_TIME_DATE_FUNCTION.apply(record.get(INTERNAL_DATE)),
            Optional.ofNullable(record.get(SAVE_DATE)).map(LOCAL_DATE_TIME_DATE_FUNCTION),
            PostgresMessageId.Factory.of(record.get(MESSAGE_ID)),
            RECORD_TO_THREAD_ID_FUNCTION.apply(record));

    Function<Record, ComposedMessageIdWithMetaData> RECORD_TO_COMPOSED_MESSAGE_ID_WITH_META_DATA_FUNCTION = record -> ComposedMessageIdWithMetaData
        .builder()
        .composedMessageId(new ComposedMessageId(PostgresMailboxId.of(record.get(MAILBOX_ID)),
            PostgresMessageId.Factory.of(record.get(MESSAGE_ID)),
            MessageUid.of(record.get(MESSAGE_UID))))
        .threadId(RECORD_TO_THREAD_ID_FUNCTION.apply(record))
        .flags(RECORD_TO_FLAGS_FUNCTION.apply(record))
        .modSeq(ModSeq.of(record.get(MOD_SEQ)))
        .build();

    Function<Record, Properties> RECORD_TO_PROPERTIES_FUNCTION = record -> {
        PropertyBuilder property = new PropertyBuilder();

        property.setMediaType(record.get(PostgresMessageDataDefinition.MessageTable.MIME_TYPE));
        property.setSubType(record.get(PostgresMessageDataDefinition.MessageTable.MIME_SUBTYPE));
        property.setTextualLineCount(Optional.ofNullable(record.get(PostgresMessageDataDefinition.MessageTable.TEXTUAL_LINE_COUNT))
            .map(Long::valueOf)
            .orElse(null));

        property.setContentDescription(record.get(CONTENT_DESCRIPTION));
        property.setContentDispositionType(record.get(CONTENT_DISPOSITION_TYPE));
        property.setContentID(record.get(CONTENT_ID));
        property.setContentMD5(record.get(CONTENT_MD5));
        property.setContentTransferEncoding(record.get(CONTENT_TRANSFER_ENCODING));
        property.setContentLocation(record.get(CONTENT_LOCATION));
        property.setContentLanguage(Optional.ofNullable(record.get(CONTENT_LANGUAGE)).map(List::of).orElse(null));
        property.setContentDispositionParameters(record.get(CONTENT_DISPOSITION_PARAMETERS).data());
        property.setContentTypeParameters(record.get(CONTENT_TYPE_PARAMETERS).data());
        return property.build();
    };

    Function<byte[], Content> BYTE_TO_CONTENT_FUNCTION = contentAsBytes -> new Content() {
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(contentAsBytes);
        }

        @Override
        public long size() {
            return contentAsBytes.length;
        }
    };

    Function<MessageMapper.FetchType, PostgresMailboxMessageFetchStrategy> FETCH_TYPE_TO_FETCH_STRATEGY = fetchType -> {
        switch (fetchType) {
            case METADATA:
            case ATTACHMENTS_METADATA:
                return PostgresMailboxMessageFetchStrategy.METADATA;
            case HEADERS:
            case FULL:
                return PostgresMailboxMessageFetchStrategy.FULL;
            default:
                throw new RuntimeException("Unknown FetchType " + fetchType);
        }
    };
}
