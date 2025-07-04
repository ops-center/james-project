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

package org.apache.james.mailbox.postgres.mail;

import static org.jooq.impl.DSL.foreignKey;

import java.time.LocalDateTime;
import java.util.UUID;

import org.apache.james.backends.postgres.PostgresCommons.DataTypes;
import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.backends.postgres.PostgresIndex;
import org.apache.james.backends.postgres.PostgresTable;
import org.apache.james.mailbox.postgres.mail.dto.AttachmentsDTO;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.postgres.extensions.types.Hstore;

public interface PostgresMessageDataDefinition {

    Field<UUID> MESSAGE_ID = DSL.field("message_id", SQLDataType.UUID.notNull());
    Field<LocalDateTime> INTERNAL_DATE = DSL.field("internal_date", DataTypes.TIMESTAMP);
    Field<Long> SIZE = DSL.field("size", SQLDataType.BIGINT.notNull());

    interface MessageTable {
        Table<Record> TABLE_NAME = DSL.table("message");
        Field<UUID> MESSAGE_ID = PostgresMessageDataDefinition.MESSAGE_ID;
        Field<String> BODY_BLOB_ID = DSL.field("body_blob_id", SQLDataType.VARCHAR(200).notNull());
        Field<String> MIME_TYPE = DSL.field("mime_type", SQLDataType.VARCHAR(200));
        Field<String> MIME_SUBTYPE = DSL.field("mime_subtype", SQLDataType.VARCHAR(200));
        Field<LocalDateTime> INTERNAL_DATE = PostgresMessageDataDefinition.INTERNAL_DATE;
        Field<Long> SIZE = PostgresMessageDataDefinition.SIZE;
        Field<Integer> BODY_START_OCTET = DSL.field("body_start_octet", SQLDataType.INTEGER.notNull());
        Field<byte[]> HEADER_CONTENT = DSL.field("header_content", SQLDataType.BLOB.notNull());
        Field<Integer> TEXTUAL_LINE_COUNT = DSL.field("textual_line_count", SQLDataType.INTEGER);

        Field<String> CONTENT_DESCRIPTION = DSL.field("content_description", SQLDataType.VARCHAR(200));
        Field<String> CONTENT_LOCATION = DSL.field("content_location", SQLDataType.VARCHAR(200));
        Field<String> CONTENT_TRANSFER_ENCODING = DSL.field("content_transfer_encoding", SQLDataType.VARCHAR(200));
        Field<String> CONTENT_DISPOSITION_TYPE = DSL.field("content_disposition_type", SQLDataType.VARCHAR(200));
        Field<String> CONTENT_ID = DSL.field("content_id", SQLDataType.VARCHAR(200));
        Field<String> CONTENT_MD5 = DSL.field("content_md5", SQLDataType.VARCHAR(200));
        Field<String[]> CONTENT_LANGUAGE = DSL.field("content_language", DataTypes.STRING_ARRAY);
        Field<Hstore> CONTENT_TYPE_PARAMETERS = DSL.field("content_type_parameters", DataTypes.HSTORE);
        Field<Hstore> CONTENT_DISPOSITION_PARAMETERS = DSL.field("content_disposition_parameters", DataTypes.HSTORE);
        Field<AttachmentsDTO> ATTACHMENT_METADATA = DSL.field("attachment_metadata",
            SQLDataType.JSONB
                .asConvertedDataType(new AttachmentsDTO.AttachmentsDTOBinding()));


        PostgresTable TABLE = PostgresTable.name(TABLE_NAME.getName())
            .createTableStep(((dsl, tableName) -> dsl.createTableIfNotExists(tableName)
                .column(MESSAGE_ID)
                .column(BODY_BLOB_ID)
                .column(MIME_TYPE)
                .column(MIME_SUBTYPE)
                .column(INTERNAL_DATE)
                .column(SIZE)
                .column(BODY_START_OCTET)
                .column(HEADER_CONTENT)
                .column(TEXTUAL_LINE_COUNT)
                .column(CONTENT_DESCRIPTION)
                .column(CONTENT_LOCATION)
                .column(CONTENT_TRANSFER_ENCODING)
                .column(CONTENT_DISPOSITION_TYPE)
                .column(CONTENT_ID)
                .column(CONTENT_MD5)
                .column(CONTENT_LANGUAGE)
                .column(CONTENT_TYPE_PARAMETERS)
                .column(CONTENT_DISPOSITION_PARAMETERS)
                .column(ATTACHMENT_METADATA)
                .constraint(DSL.primaryKey(MESSAGE_ID))
                .comment("Holds the metadata of a mail")))
            .supportsRowLevelSecurity()
            .build();
    }

    interface MessageToMailboxTable {
        Table<Record> TABLE_NAME = DSL.table("message_mailbox");
        Field<UUID> MAILBOX_ID = DSL.field("mailbox_id", SQLDataType.UUID.notNull());
        Field<Long> MESSAGE_UID = DSL.field("message_uid", SQLDataType.BIGINT.notNull());
        Field<Long> MOD_SEQ = DSL.field("mod_seq", SQLDataType.BIGINT.notNull());
        Field<UUID> MESSAGE_ID = PostgresMessageDataDefinition.MESSAGE_ID;
        Field<UUID> THREAD_ID = DSL.field("thread_id", SQLDataType.UUID);
        Field<LocalDateTime> INTERNAL_DATE = PostgresMessageDataDefinition.INTERNAL_DATE;
        Field<Long> SIZE = PostgresMessageDataDefinition.SIZE;
        Field<Boolean> IS_DELETED = DSL.field("is_deleted", SQLDataType.BOOLEAN.nullable(false)
            .defaultValue(DSL.field("false", SQLDataType.BOOLEAN)));
        Field<Boolean> IS_ANSWERED = DSL.field("is_answered", SQLDataType.BOOLEAN.nullable(false));
        Field<Boolean> IS_DRAFT = DSL.field("is_draft", SQLDataType.BOOLEAN.nullable(false));
        Field<Boolean> IS_FLAGGED = DSL.field("is_flagged", SQLDataType.BOOLEAN.nullable(false));
        Field<Boolean> IS_RECENT = DSL.field("is_recent", SQLDataType.BOOLEAN.nullable(false));
        Field<Boolean> IS_SEEN = DSL.field("is_seen", SQLDataType.BOOLEAN.nullable(false));
        Field<String[]> USER_FLAGS = DSL.field("user_flags", DataTypes.STRING_ARRAY);
        Field<LocalDateTime> SAVE_DATE = DSL.field("save_date", DataTypes.TIMESTAMP);

        String REMOVE_ELEMENTS_FROM_ARRAY_FUNCTION_NAME = "remove_elements_from_array";
        String CREATE_ARRAY_REMOVE_JAMES_FUNCTION =
            "CREATE OR REPLACE FUNCTION " + REMOVE_ELEMENTS_FROM_ARRAY_FUNCTION_NAME + "(\n" +
                "    source text[],\n" +
                "    elements_to_remove text[])\n" +
                "    RETURNS text[]\n" +
                "AS\n" +
                "$$\n" +
                "DECLARE\n" +
                "    result text[];\n" +
                "BEGIN\n" +
                "    select array_agg(elements) INTO result\n" +
                "    from (select unnest(source)\n" +
                "          except\n" +
                "          select unnest(elements_to_remove)) t (elements);\n" +
                "    RETURN result;\n" +
                "END;\n" +
                "$$ LANGUAGE plpgsql;";

        PostgresTable TABLE = PostgresTable.name(TABLE_NAME.getName())
            .createTableStep(((dsl, tableName) -> dsl.createTableIfNotExists(tableName)
                .column(MAILBOX_ID)
                .column(MESSAGE_UID)
                .column(MOD_SEQ)
                .column(MESSAGE_ID)
                .column(THREAD_ID)
                .column(INTERNAL_DATE)
                .column(SIZE)
                .column(IS_DELETED)
                .column(IS_ANSWERED)
                .column(IS_DRAFT)
                .column(IS_FLAGGED)
                .column(IS_RECENT)
                .column(IS_SEEN)
                .column(USER_FLAGS)
                .column(SAVE_DATE)
                .constraints(DSL.primaryKey(MAILBOX_ID, MESSAGE_UID),
                    foreignKey(MESSAGE_ID).references(MessageTable.TABLE_NAME, MessageTable.MESSAGE_ID))
                .comment("Holds mailbox and flags for each message")))
            .supportsRowLevelSecurity()
            .addAdditionalAlterQueries(CREATE_ARRAY_REMOVE_JAMES_FUNCTION)
            .build();

        PostgresIndex MESSAGE_ID_INDEX = PostgresIndex.name("message_mailbox_message_id_index")
            .createIndexStep((dsl, indexName) -> dsl.createIndexIfNotExists(indexName)
                .on(TABLE_NAME, MESSAGE_ID));

        PostgresIndex MAILBOX_ID_MESSAGE_UID_INDEX = PostgresIndex.name("mailbox_id_mail_uid_index")
            .createIndexStep((dsl, indexName) -> dsl.createIndexIfNotExists(indexName)
                .on(TABLE_NAME, MAILBOX_ID, MESSAGE_UID.asc()));
        PostgresIndex MAILBOX_ID_IS_SEEN_MESSAGE_UID_INDEX = PostgresIndex.name("mailbox_id_is_seen_mail_uid_index")
            .createIndexStep((dsl, indexName) -> dsl.createIndexIfNotExists(indexName)
                .on(TABLE_NAME, MAILBOX_ID, IS_SEEN, MESSAGE_UID.asc()));
        PostgresIndex MAILBOX_ID_IS_RECENT_MESSAGE_UID_INDEX = PostgresIndex.name("mailbox_id_is_recent_mail_uid_index")
            .createIndexStep((dsl, indexName) -> dsl.createIndexIfNotExists(indexName)
                .on(TABLE_NAME, MAILBOX_ID, IS_RECENT, MESSAGE_UID.asc()));
        PostgresIndex MAILBOX_ID_IS_DELETE_MESSAGE_UID_INDEX = PostgresIndex.name("mailbox_id_is_delete_mail_uid_index")
            .createIndexStep((dsl, indexName) -> dsl.createIndexIfNotExists(indexName)
                .on(TABLE_NAME, MAILBOX_ID, IS_DELETED, MESSAGE_UID.asc()));
        PostgresIndex MAILBOX_THREAD_DATE_INDEX = PostgresIndex.name("mailbox_thread_internal_date_index")
                .createIndexStep((dsl, indexName) -> dsl.createIndexIfNotExists(indexName)
                        .on(TABLE_NAME, MAILBOX_ID, THREAD_ID, INTERNAL_DATE.desc()));
    }

    PostgresDataDefinition MODULE = PostgresDataDefinition.builder()
        .addTable(MessageTable.TABLE)
        .addTable(MessageToMailboxTable.TABLE)
        .addIndex(MessageToMailboxTable.MESSAGE_ID_INDEX)
        .addIndex(MessageToMailboxTable.MAILBOX_ID_MESSAGE_UID_INDEX)
        .addIndex(MessageToMailboxTable.MAILBOX_ID_IS_SEEN_MESSAGE_UID_INDEX)
        .addIndex(MessageToMailboxTable.MAILBOX_ID_IS_RECENT_MESSAGE_UID_INDEX)
        .addIndex(MessageToMailboxTable.MAILBOX_ID_IS_DELETE_MESSAGE_UID_INDEX)
        .addIndex(MessageToMailboxTable.MAILBOX_THREAD_DATE_INDEX)
        .build();

}
